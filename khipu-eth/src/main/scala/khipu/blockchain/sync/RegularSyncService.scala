package khipu.blockchain.sync

import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import akka.pattern.AskTimeoutException
import akka.pattern.ask
import java.util.concurrent.ThreadLocalRandom
import khipu.BroadcastNewBlocks
import khipu.DataWord
import khipu.blockchain.sync
import khipu.blockchain.sync.HandshakedPeersService.BlacklistPeer
import khipu.blockchain.sync.HandshakedPeersService.ResetBlacklistCount
import khipu.config.KhipuConfig
import khipu.domain.Block
import khipu.domain.BlockHeader
import khipu.ledger.Ledger.BlockExecutionError
import khipu.ledger.Ledger.BlockResult
import khipu.ledger.Ledger.MissingNodeExecptionError
import khipu.ledger.Ledger.ValidationBeforeExecError
import khipu.network.Peer
import khipu.network.PeerEntity
import khipu.network.handshake.EtcHandshake.PeerInfo
import khipu.network.p2p.messages.CommonMessages.NewBlock
import khipu.network.p2p.messages.PV62
import khipu.transactions.PendingTransactionsService
import khipu.ommers.OmmersPool
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.control.NoStackTrace

object RegularSyncService {
  private case object ResumeRegularSyncTask
  private case object ResumeRegularSyncTick

  private case class ProcessBlockHeaders(peer: Peer, headers: List[BlockHeader])
  private case class ProcessBlockBodies(peer: Peer, bodies: List[PV62.BlockBody])

  private case class ExecuteAndInsertBlocksAborted(parentTotalDifficulty: DataWord, newBlocks: Vector[NewBlock], errors: Vector[BlockExecutionError]) extends Throwable with NoStackTrace
}
trait RegularSyncService { _: SyncService =>
  import context.dispatcher
  import RegularSyncService._
  import KhipuConfig.Sync._

  private def tf(n: Int) = "%1$4d".format(n) // tx
  private def xf(n: Double) = "%1$6.1f".format(n) // tps
  private def pf(n: Double) = "%1$6.2f".format(n) // percent
  private def pf2(n: Double) = "%1$5.2f".format(n) // percent less than 100%
  private def ef(n: Double) = "%1$6.3f".format(n) // elapse time
  private def ef2(n: Double) = "%1$5.3f".format(n) // elapse time
  private def gf(n: Double) = "%1$7.2f".format(n) // gas
  private def f6(n: Int) = "%1$6d".format(n) // payload
  private def f5(n: Int) = "%1$5d".format(n) // cache read count 

  // Should keep newer block to be at the front
  private var workingHeaders = List[BlockHeader]()

  def startRegularSync() {
    log.info("Starting regular block synchronization")
    appStateStorage.fastSyncDone()
    setCurrBlockHeaderForChecking()
    blockchain.swithToWithUnconfirmed()
    validators.blockHeaderValidator.syncDone()
    context become (handleRegularSync orElse peerUpdateBehavior orElse ommersBehavior orElse stopBehavior)
    resumeRegularSync()
  }

  def handleRegularSync: Receive = {
    case ResumeRegularSyncTick =>
      workingHeaders = Nil
      requestHeaders()

    case ProcessBlockHeaders(peer, headers) =>
      processBlockHeaders(peer, headers)

    case ProcessBlockBodies(peer, bodies) =>
      processBlockBodies(peer, bodies)

    case SyncService.ReceivedMessage(peerId, message) =>
      log.debug(s"Received ${message.getClass.getName} from $peerId")

    case SyncService.MinedBlock(block) =>
    //TODO
    //processMinedBlock(block)
  }

  private def resumeRegularSync() {
    self ! ResumeRegularSyncTick
  }

  private def scheduleResume() {
    timers.startSingleTimer(ResumeRegularSyncTask, ResumeRegularSyncTick, checkForNewBlockInterval)
  }

  private def blockPeerAndResumeWithAnotherOne(currPeer: Peer, reason: String, force: Boolean = false) {
    self ! BlacklistPeer(currPeer.id, reason, force)
    self ! ResumeRegularSyncTick
  }

  private def requestHeaders() {
    bestPeer match {
      case Some(peer) =>
        val nextBlockNumber = storages.bestBlockNumber + 1

        log.debug(s"Request block headers beginning at $nextBlockNumber via best peer $peer")

        requestingHeaders(peer, None, Left(nextBlockNumber), blockHeadersPerRequest, skip = 0, reverse = false)(syncRequestTimeout) andThen {
          case Success(Some(BlockHeadersResponse(peerId, headers, true))) =>
            log.debug(s"Got block headers from $peer")
            self ! ProcessBlockHeaders(peer, headers)

          case Success(Some(BlockHeadersResponse(peerId, _, false))) =>
            blockPeerAndResumeWithAnotherOne(peer, s"Got error in block headers response for requested: $nextBlockNumber")

          case Success(None) =>
            scheduleResume()

          case Failure(e: AskTimeoutException) =>
            blockPeerAndResumeWithAnotherOne(peer, s"${e.getMessage}")

          case Failure(e) =>
            blockPeerAndResumeWithAnotherOne(peer, s"${e.getMessage}")
        }

      case None =>
        log.debug("No peers to download from")
        scheduleResume()
    }
  }

  // TODO improve mined block handling - add info that block was not included because of syncing [EC-250]
  // we allow inclusion of mined block only if we are not syncing / reorganising chain
  private def processMinedBlock(block: Block) {
    if (workingHeaders.isEmpty && !isRequesting) {
      // we are at the top of chain we can insert new block
      blockchain.getTotalDifficultyByHash(block.header.parentHash) match {
        case Some(parentTd) if block.header.number > storages.bestBlockNumber =>
          // just insert block and let resolve it with regular download
          val f = executeAndInsertBlock(block, parentTd, isBatch = false) andThen {
            case Success(Right(newBlock)) =>
              // broadcast new block
              handshakedPeers foreach {
                case (peerId, (peer, peerInfo)) => peer.entity ! PeerEntity.MessageToPeer(peerId, newBlock)
              }

            case Success(Left(error)) =>

            case Failure(e)           =>
          }
          Await.result(f, Duration.Inf)
        case _ =>
          log.error("Failed to add mined block")
      }
    } else {
      ommersPool ! OmmersPool.AddOmmers(List(block.header))
    }
  }

  private def processBlockHeaders(peer: Peer, headers: List[BlockHeader]) {
    if (workingHeaders.isEmpty) {
      if (headers.nonEmpty) {
        workingHeaders = headers
        doProcessBlockHeaders(peer, headers)
      } else {
        // no new headers to process, schedule to ask again in future, we are at the top of chain
        scheduleResume()
      }
    } else {
      // TODO limit max branch depth? [EC-248]
      if (headers.nonEmpty && headers.last.hash == workingHeaders.head.parentHash) {
        // should insert before workingHeaders
        workingHeaders = headers ::: workingHeaders
        doProcessBlockHeaders(peer, workingHeaders)
      } else {
        blockPeerAndResumeWithAnotherOne(peer, "Did not get previous blocks, there is no way to resolve, blacklist peer and continue download")
      }
    }
  }

  private var isUnderReorg = false
  private def doProcessBlockHeaders(peer: Peer, headers: List[BlockHeader]) {
    if (checkHeaders(headers)) {
      val firstHeader = headers.head
      blockchain.getBlockHeaderByNumber(firstHeader.number - 1) match {
        case Some(parent) =>
          if (parent.hash == firstHeader.parentHash) {
            // we have same chain prefix
            // TODO check if received headers override already confirmed blocks, if so, should prevent it. 
            val oldBranch = getPrevBlocks(headers)
            val oldBranchTotalDifficulty = oldBranch.map(_.header.difficulty).foldLeft(DataWord.Zero)(_ + _)
            val newBranchTotalDifficulty = headers.map(_.difficulty).foldLeft(DataWord.Zero)(_ + _)

            if (newBranchTotalDifficulty.compareTo(oldBranchTotalDifficulty) > 0) { // TODO what about == 0 ?
              if (isUnderReorg) {
                blockchain.clearUnconfirmed()
              }
              isUnderReorg = false

              val transactionsToAdd = oldBranch.flatMap(_.body.transactionList)
              pendingTransactionsService ! PendingTransactionsService.AddTransactions(transactionsToAdd.toList)
              val hashes = headers.take(blockBodiesPerRequest).map(_.hash)

              log.debug(s"Request block bodies from $peer")

              requestingBodies(peer, hashes)(syncRequestTimeout.plus((hashes.size * 100).millis)) andThen {
                case Success(Some(BlockBodiesResponse(peerId, remainingHashes, receivedHashes, bodies))) =>
                  log.debug(s"Got block bodies from $peer")
                  self ! ResetBlacklistCount(peer.id)
                  self ! ProcessBlockBodies(peer, bodies)

                case Success(None) =>
                  blockPeerAndResumeWithAnotherOne(peer, s"Got empty block bodies response for known hashes: ${hashes.map(_.hexString)}")

                case Failure(e: AskTimeoutException) =>
                  blockPeerAndResumeWithAnotherOne(peer, s"${e.getMessage}")

                case Failure(e) =>
                  blockPeerAndResumeWithAnotherOne(peer, s"${e.getMessage}")

              } andThen {
                case _ => oldBranch.headOption foreach { block => ommersPool ! OmmersPool.AddOmmers(List(block.header)) } // add first block from branch as ommer
              }

            } else {
              isUnderReorg = false
              // add first block from branch as ommer
              ommersPool ! OmmersPool.AddOmmers(List(firstHeader))
              scheduleResume()
            }

          } else {
            log.info(s"[sync] Received branch block ${headers.head.number} from ${peer.id}, resolving fork ...")

            if (isUnderReorg) {
              // the new branch with blockResolveDepth backward is still not match the first header's parent hash.
              blockPeerAndResumeWithAnotherOne(peer, s"Got reorg error in block headers response for requested: ${firstHeader.parentHash}", force = true)
            } else {
              isUnderReorg = true
              requestingHeaders(peer, None, Right(firstHeader.parentHash), blockResolveDepth, skip = 0, reverse = true)(syncRequestTimeout) andThen {
                case Success(Some(BlockHeadersResponse(peerId, headers, true))) =>
                  self ! ResetBlacklistCount(peer.id)
                  self ! ProcessBlockHeaders(peer, headers)

                case Success(Some(BlockHeadersResponse(peerId, List(), false))) =>
                  blockPeerAndResumeWithAnotherOne(peer, s"Got error in block headers response for requested: ${firstHeader.parentHash}")

                case Success(None) =>
                  scheduleResume()

                case Failure(e: AskTimeoutException) =>
                  blockPeerAndResumeWithAnotherOne(peer, s"timeout, ${e.getMessage}")

                case Failure(e) =>
                  blockPeerAndResumeWithAnotherOne(peer, s"${e.getMessage}")
              }
            }
          }

        case None =>
          log.info(s"[warn] Received block header ${headers.head.number} without parent from ${peer.id}")
          blockPeerAndResumeWithAnotherOne(peer, s"Got block header ${headers.head.number} that does not have parent")
      }
    } else {
      blockPeerAndResumeWithAnotherOne(peer, s"Got block headers begin at ${headers.head.number} to ${headers.last.number} that are not consistent")
    }
  }

  private def getPrevBlocks(headers: List[BlockHeader]) = getPrevBlocks_recurse(headers, Vector())
  @tailrec private def getPrevBlocks_recurse(headers: List[BlockHeader], blocks: Vector[Block]): Vector[Block] = {
    headers match {
      case Nil => blocks
      case block :: tail =>
        blockchain.getBlockByNumber(block.number) match {
          case Some(block) => getPrevBlocks_recurse(tail, blocks :+ block)
          case None        => blocks
        }
    }
  }

  private def processBlockBodies(peer: Peer, bodies: Seq[PV62.BlockBody]) {
    doProcessBlockBodies(peer, bodies)
  }

  private def doProcessBlockBodies(peer: Peer, bodies: Seq[PV62.BlockBody]) {
    if (bodies.nonEmpty && workingHeaders.nonEmpty) {
      val blocks = workingHeaders.zip(bodies).map { case (header, body) => Block(header, body) }

      ledger.validateBlocksBeforeExecution(blocks, validators) andThen {
        case Success((preValidBlocks, None)) =>
          val parentTd = blockchain.getTotalDifficultyByHash(preValidBlocks.head.header.parentHash) getOrElse {
            // TODO: Investigate if we can recover from this error (EC-165)
            throw new IllegalStateException(s"No total difficulty for the latest block with number ${blocks.head.header.number - 1} (and hash ${blocks.head.header.parentHash.hexString})")
          }

          val start = System.nanoTime
          executeAndInsertBlocks(preValidBlocks, parentTd, preValidBlocks.size > 1) andThen {
            case Success((_, newBlocks, errors)) =>
              val elapsed = (System.nanoTime - start) / 1000000000.0

              if (newBlocks.nonEmpty) {
                setCurrBlockHeaderForChecking()

                broadcastNewBlocks(newBlocks)
              }

              errors match {
                case Vector() =>
                  workingHeaders = workingHeaders.drop(blocks.length)
                  if (workingHeaders.nonEmpty) {
                    val hashes = workingHeaders.take(blockBodiesPerRequest).map(_.hash)

                    log.debug(s"Request block bodies from $peer")

                    requestingBodies(peer, hashes)(syncRequestTimeout.plus((hashes.size * 100).millis)) andThen {
                      case Success(Some(BlockBodiesResponse(peerId, remainingHashes, receivedHashes, bodies))) =>
                        log.debug(s"Got block bodies from $peer")
                        self ! ResetBlacklistCount(peer.id)
                        self ! ProcessBlockBodies(peer, bodies)

                      case Success(None) =>
                        blockPeerAndResumeWithAnotherOne(peer, s"Got empty block bodies response for known hashes: ${hashes.map(_.hexString)}")

                      case Failure(e: AskTimeoutException) =>
                        blockPeerAndResumeWithAnotherOne(peer, s"${e.getMessage}")

                      case Failure(e) =>
                        blockPeerAndResumeWithAnotherOne(peer, s"${e.getMessage}")
                    }
                  } else {
                    scheduleResume()
                  }

                case Vector(error @ MissingNodeExecptionError(number, hash, storage), _*) =>
                  log.info(s"[warn] Execution error ${error.reason}, in block ${error.blockNumber}, try to fetch from ${peer.id}")

                  requestingNodeData(nodeOkPeer.getOrElse(peer), hash)(10.seconds) andThen {
                    case Success(Some(NodeDataResponse(peerId, value))) => storage.put(hash, value.toArray)
                    case Success(None)                                  => log.debug(s"Cannot get node $hash from ${peer.id}")
                    case Failure(e)                                     => nodeErrorPeers += peer
                  } andThen {
                    case _ => resumeRegularSync()
                  }

                case Vector(error, _*) =>
                  log.error(s"[sync] Execution error $error, in block ${error.blockNumber}")
                  blockPeerAndResumeWithAnotherOne(peer, s"Block execution error: $error, in block ${error.blockNumber} from ${peer.id}, will sync from another peer")
              }

            case Failure(e) =>
              log.error(e, e.getMessage)
              scheduleResume()
          }

        case Success((_, Some(error))) =>
          log.info(s"[warn] Before execution validate error: $error, in block ${error.blockNumber} from ${peer.id}, will sync from another peer")
          blockPeerAndResumeWithAnotherOne(peer, s"Validate blocks before execution error: $error, in block ${error.blockNumber}")

        case Failure(e) =>
          log.error(e, e.getMessage)
          scheduleResume()
      }

    } else {
      blockPeerAndResumeWithAnotherOne(peer, "Got empty response for bodies from peer but we got block headers earlier")
    }
  }

  /**
   * Inserts and executes all the blocks, up to the point to which one of them fails (or we run out of blocks).
   * If the execution of any block were to fail, newBlocks only contains the NewBlock msgs for all the blocks executed before it,
   * and only the blocks successfully executed are inserted into the blockchain.
   *
   * @param blocks to execute
   * @param blockParentTd, td of the parent of the blocks.head block
   * @param newBlocks which, after adding the corresponding NewBlock msg for blocks, will be broadcasted
   * @return list of NewBlocks to broadcast (one per block successfully executed) and  errors if happened during execution
   */
  private def executeAndInsertBlocks(blocks: Vector[Block], parentTd: DataWord, isBatch: Boolean): Future[(DataWord, Vector[NewBlock], Vector[BlockExecutionError])] = {
    blocks.foldLeft(Future.successful(parentTd, Vector[NewBlock](), Vector[BlockExecutionError]())) {
      case (prevFuture, block) =>
        prevFuture flatMap {
          case (parentTotalDifficulty, newBlocks, Vector()) =>
            executeAndInsertBlock(block, parentTotalDifficulty, isBatch) map {
              case Right(newBlock) =>
                // check blockHashToDelete
                //blockchain.getBlockHeaderByNumber(block.header.number).map(_.hash).filter(_ != block.header.hash) foreach blockchain.removeBlock

                (newBlock.totalDifficulty, newBlocks :+ newBlock, Vector())
              case Left(error) =>
                (parentTotalDifficulty, newBlocks, Vector(error))
            }

          case (parentTotalDifficulty, newBlocks, errors) =>
            Future.failed(ExecuteAndInsertBlocksAborted(parentTotalDifficulty, newBlocks, errors))
        }
    } recover {
      case ExecuteAndInsertBlocksAborted(parentTotalDifficulty, newBlocks, errors) =>
        (parentTotalDifficulty, newBlocks, errors)
    }
  }

  private def executeAndInsertBlock(block: Block, parentTd: DataWord, isBatch: Boolean): Future[Either[BlockExecutionError, NewBlock]] = {
    try {
      val start = System.nanoTime
      ledger.executeBlock(block, validators) map {
        case Right(BlockResult(world, _, receipts, stats)) =>
          val dbReadTime = (stats.dbReadTimePerc * (System.nanoTime - start)) / 1000000000.0

          val newTd = parentTd + block.header.difficulty

          val start1 = System.nanoTime
          blockchain.saveNewBlock(world, block, receipts, newTd)
          val dbWriteTime = (System.nanoTime - start1) / 1000000000.0
          log.debug(s"${block.header.number} persisted in ${(System.nanoTime - start1) / 1000000}ms")

          pendingTransactionsService ! PendingTransactionsService.RemoveTransactions(block.body.transactionList)
          ommersPool ! OmmersPool.RemoveOmmers((block.header +: block.body.uncleNodesList).toList)

          val nTx = block.body.transactionList.size
          val gasUsed = block.header.gasUsed / 1048576.0
          val payloadSize = block.body.transactionList.map(_.tx.payload.size).foldLeft(0)(_ + _)
          val elapsed = (System.nanoTime - start) / 1000000000.0
          val parallelPerc = stats.parallelRate * 100
          val cacheHitRates = stats.cacheHitRates.map(x => s"${pf(x)}%").mkString(" ")
          val cacheReadCount = stats.cacheReadCount.toInt
          log.info(s"[sync]${if (isBatch) "+" else " "}Executed #${block.header.number} (${tf(nTx)} tx) in ${ef(elapsed)}s, ${xf(nTx / elapsed)} tx/s, ${gf(gasUsed / elapsed)} mgas/s, payload ${f6(payloadSize)}, parallel ${pf(parallelPerc)}%, r/w(s) ${ef2(dbReadTime)}/${ef2(dbWriteTime)}, cache(${f5(cacheReadCount)}) ${cacheHitRates}")
          Right(NewBlock(block, newTd))

        case Left(err) =>
          log.warning(s"Failed to execute mined block because of $err")
          Left(err)
      }

    } catch { // TODO need detailed here
      case ex: Throwable =>
        log.error(ex, s"Failed to execute mined block because of exception: ${ex.getMessage}")
        Future.successful(Left(ValidationBeforeExecError(block.header.number, ex.getMessage)))
    }
  }

  private def checkHeaders(headers: Seq[BlockHeader]): Boolean = {
    headers.zip(headers.tail).forall { case (parent, child) => parent.hash == child.parentHash && parent.number + 1 == child.number }
  }

  private def bestPeer: Option[Peer] = {
    val peersToUse = goodPeers.collect {
      case (peer, PeerInfo(_, totalDifficulty, true, _)) => (peer, totalDifficulty)
    }

    if (peersToUse.nonEmpty) {
      val candicates = peersToUse.toList.sortBy { case (_, td) => -td }.take(3).map(_._1).toArray
      Some(nextCandicate(candicates))
    } else {
      None
    }
  }

  private var nodeErrorPeers = Set[Peer]()
  private def nodeOkPeer: Option[Peer] = {
    val peersToUse = goodPeers.collect {
      case (peer, PeerInfo(_, totalDifficulty, true, _)) => (peer, totalDifficulty)
    } -- nodeErrorPeers

    if (peersToUse.nonEmpty) {
      val candicates = peersToUse.toList.sortBy { case (_, td) => -td }.take(3).map(_._1).toArray
      Some(nextCandicate(candicates))
    } else {
      None
    }
  }

  private def nextCandicate(candicates: Array[Peer]) = candicates(nextCandicateIndex(0, candicates.length))
  private def nextCandicateIndex(low: Int, high: Int) = { // >= low and < high
    val rnd = ThreadLocalRandom.current()
    rnd.nextInt(high - low) + low
  }

  /**
   * Broadcasts various NewBlock's messages to handshaked peers, considering that a block should not be sent to a peer
   * that is thought to know it. In the current implementation we send every block to every peer (that doesn't know
   * this block)
   *
   * @param newBlocks, blocks to broadcast
   * @param handshakedPeers
   */
  //FIXME: Decide block propagation algorithm (for now we send block to every peer) [EC-87]
  def broadcastNewBlocks(newBlocks: Seq[NewBlock]) {
    mediator ! Publish(sync.NewBlockTopic, BroadcastNewBlocks(newBlocks))
  }
}

package the.walrus.ckite.states

import org.slf4j.LoggerFactory
import the.walrus.ckite.rpc.RequestVote
import the.walrus.ckite.Cluster
import the.walrus.ckite.rpc.WriteCommand
import the.walrus.ckite.rpc.RequestVoteResponse
import the.walrus.ckite.rpc.AppendEntriesResponse
import the.walrus.ckite.rpc.AppendEntries
import the.walrus.ckite.rpc.EnterJointConsensus
import the.walrus.ckite.rpc.Command
import the.walrus.ckite.util.Logging
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.SynchronousQueue
import com.twitter.concurrent.NamedPoolThreadFactory
import the.walrus.ckite.util.CKiteConversions._
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference


/** 	•! Increment currentTerm, vote for self
 * •! Reset election timeout
 * •! Send RequestVote RPCs to all other servers, wait for either:
 * •! Votes received from majority of servers: become leader
 * •! AppendEntries RPC received from new leader: step
 * down
 * •! Election timeout elapses without election resolution:
 * increment term, start new election
 * •! Discover higher term: step down (§5.1)
 */
class Candidate(cluster: Cluster) extends State {

  val election = new Election(cluster)
  
  override def begin(term: Int) = {
    val inTerm = cluster.local.incrementTerm
    cluster.setNoLeader
    
    LOG.info(s"Start election")
    election.start(inTerm)
  }
  
  override def on(appendEntries: AppendEntries): AppendEntriesResponse = {
    if (appendEntries.term < cluster.local.term) {
      AppendEntriesResponse(cluster.local.term, false)
    }
    else {
      election.abort
      stepDown(Some(appendEntries.leaderId), appendEntries.term)
      cluster.local on appendEntries 
    }
  }

  override def on(requestVote: RequestVote): RequestVoteResponse = {
    if (requestVote.term <= cluster.local.term) {
      RequestVoteResponse(cluster.local.term, false)
    } else {
      election.abort
      stepDown(None, requestVote.term)
      cluster.local on requestVote
    }
  }
  
  override def on[T](command: Command): T = {
    cluster.forwardToLeader[T](command)
  }
  
  override def toString = "Candidate"
    
  override protected def getCluster: Cluster = cluster
  
}

class Election(cluster: Cluster) extends Logging {
  
  val executor =  new ThreadPoolExecutor(1, 1,
                                      60L, TimeUnit.SECONDS,
                                      new SynchronousQueue[Runnable](),
                                      new NamedPoolThreadFactory("CandidateElection", true))
  val electionFutureTask = new AtomicReference[Future[_]]()
  
  def start(inTerm: Int) = {
    val task:Runnable =  {
	        cluster.local voteForMyself
	    
	     val votes = (cluster collectVotes) :+ cluster.local
	    
	    LOG.debug(s"Got ${votes.size} votes in a majority of ${cluster.majority}")
	    if (cluster.reachMajority(votes)) {
	      cluster.local becomeLeader inTerm
	    } else {
	      LOG.info(s"Not enough votes to be a Leader")
	      cluster.local becomeFollower inTerm
	    }
    }
    val future:Future[_]  = executor.submit(task)
    electionFutureTask.set(future)
  }
  
  def abort = {
    LOG.info("Abort Election")
    val future = electionFutureTask.get()
    if (future != null) future.cancel(true)
  }
  
}
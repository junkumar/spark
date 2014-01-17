package catalyst
package execution

import scala.collection.mutable

import org.apache.spark.rdd.RDD

import errors._
import expressions._
import plans._

import org.apache.spark.rdd.SharkPairRDDFunctions._

case class SparkEquiInnerJoin(
    leftKeys: Seq[Expression],
    rightKeys: Seq[Expression],
    left: SharkPlan,
    right: SharkPlan) extends BinaryNode {


  override def requiredChildPartitioning =
    ClusteredDistribution(leftKeys) :: ClusteredDistribution(rightKeys) :: Nil

  def output = left.output ++ right.output

  def execute() = attachTree(this, "execute") {
    val leftWithKeys = left.execute.map { row =>
      val joinKeys = leftKeys.map(Evaluate(_, Vector(row)))
      logger.debug(s"Generated left join keys [${leftKeys.mkString(",")}] => [${joinKeys.mkString(",")}] given row $row")
      (joinKeys, row)
    }

    val rightWithKeys = right.execute.map { row =>
      val joinKeys = rightKeys.map(Evaluate(_, Vector(EmptyRow, row)))
      logger.debug(s"Generated right join keys [${rightKeys.mkString(",")}] => [${joinKeys.mkString(",")}] given row $row")
      (joinKeys, row)
    }

    // Do the join.
    val joined = filterNulls(leftWithKeys).joinLocally(filterNulls(rightWithKeys))
    // Drop join keys and merge input tuples.
    joined.map { case (_, (leftTuple, rightTuple)) => buildRow(leftTuple ++ rightTuple) }
  }

  /**
   * Filters any rows where the any of the join keys is null, ensuring three-valued
   * logic for the equi-join conditions.
   */
  protected def filterNulls(rdd: RDD[(Seq[Any], Row)]) =
    rdd.filter {
      case (key: Seq[_], _) => !key.exists(_ == null)
    }
}

case class CartesianProduct(left: SharkPlan, right: SharkPlan) extends BinaryNode {
  def output = left.output ++ right.output

  def execute() = left.execute().cartesian(right.execute()).map {
    case (l: Row, r: Row) => buildRow(l ++ r)
  }
}

case class BroadcastNestedLoopJoin(
    streamed: SharkPlan, broadcast: SharkPlan, joinType: JoinType, condition: Option[Expression])
    (@transient sc: SharkContext)
  extends BinaryNode {

  override def otherCopyArgs = sc :: Nil

  def output = left.output ++ right.output

  /** The Streamed Relation */
  def left = streamed
  /** The Broadcast relation */
  def right = broadcast

  def execute() = {
    val broadcastedRelation = sc.broadcast(broadcast.execute().collect().toIndexedSeq)

    val streamedPlusMatches = streamed.execute().map { streamedRow =>
      var i = 0
      val matchedRows = new mutable.ArrayBuffer[Row]
      val includedBroadcastTuples =  new mutable.BitSet(broadcastedRelation.value.size)

      while (i < broadcastedRelation.value.size) {
        // TODO: One bitset per partition instead of per row.
        val broadcastedRow = broadcastedRelation.value(i)
        val includeRow = condition match {
          case None => true
          case Some(c) => Evaluate(c, Vector(streamedRow, broadcastedRow)).asInstanceOf[Boolean]
        }
        if (includeRow) {
          matchedRows += buildRow(streamedRow ++ broadcastedRow)
          includedBroadcastTuples += i
        }
        i += 1
      }
      val outputRows = if (matchedRows.size > 0) {
        matchedRows
      } else if (joinType == LeftOuter || joinType == FullOuter) {
        Vector(buildRow(streamedRow ++ Array.fill(right.output.size)(null)))
      } else {
        Vector()
      }
      (outputRows, includedBroadcastTuples)
    }

    val includedBroadcastTuples = streamedPlusMatches.map(_._2)
    val allIncludedBroadcastTuples =
      if (includedBroadcastTuples.count == 0)
        new scala.collection.mutable.BitSet(broadcastedRelation.value.size)
      else
        streamedPlusMatches.map(_._2).reduce(_ ++ _)

    val rightOuterMatches: Seq[Row] =
      if (joinType == RightOuter || joinType == FullOuter) {
        broadcastedRelation.value.zipWithIndex.filter {
          case (row, i) => !allIncludedBroadcastTuples.contains(i)
        }.map {
          case (row, _) => buildRow(Vector.fill(left.output.size)(null) ++ row)
        }
      } else {
        Vector()
      }

    sc.union(streamedPlusMatches.flatMap(_._1), sc.makeRDD(rightOuterMatches))
  }
}
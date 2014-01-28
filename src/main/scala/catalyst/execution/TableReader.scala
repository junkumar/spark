package catalyst.execution

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants._
import org.apache.hadoop.hive.ql.metadata.{Partition => HivePartition, Table => HiveTable}
import org.apache.hadoop.hive.ql.plan.TableDesc
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.serde2.AbstractSerDe
import org.apache.hadoop.hive.ql.exec.Utilities
import org.apache.hadoop.io.Writable
import org.apache.hadoop.fs.{Path, PathFilter}
import org.apache.hadoop.mapred.{FileInputFormat, JobConf, InputFormat}

import org.apache.spark.SerializableWritable
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.{HadoopRDD, UnionRDD, EmptyRDD, RDD}


/**
 * A trait for subclasses that handle table scans. In Shark, there is one subclass for each
 * type of table storage: HeapTableReader for Shark tables in Spark's block manager,
 * TachyonTableReader for tables in Tachyon, and HadoopTableReader for Hive tables in a filesystem.
 */
private[catalyst] sealed trait TableReader {

  def makeRDDForTable(hiveTable: HiveTable): RDD[_]

  def makeRDDForPartitionedTable(partitions: Seq[HivePartition]): RDD[_]

}


/**
 * Helper class for scanning tables stored in Hadoop - e.g., to read Hive tables that reside in the
 * data warehouse directory.
 */
private[catalyst] class HadoopTableReader(@transient _tableDesc: TableDesc, @transient _localHConf: HiveConf)
  extends TableReader {

  // Choose the minimum number of splits. If mapred.map.tasks is set, then use that unless
  // it is smaller than what Spark suggests.
  private val _minSplitsPerRDD = math.max(
    _localHConf.getInt("mapred.map.tasks", 1), SharkEnv.sc.defaultMinSplits)


  // TODO: set aws s3 credentials.

  private val _broadcastedHiveConf = SharkEnv.sc.broadcast(new SerializableWritable(_localHConf))

  def broadcastedHiveConf = _broadcastedHiveConf

  def hiveConf = _broadcastedHiveConf.value.value

  override def makeRDDForTable(hiveTable: HiveTable): RDD[_] =
    makeRDDForTable(
      hiveTable,
      _tableDesc.getDeserializerClass.asInstanceOf[Class[AbstractSerDe]],
      filterOpt = None)

  /**
   * Creates a Hadoop RDD to read data from the target table's data directory. Returns a transformed
   * RDD that contains deserialized rows.
   *
   * @param hiveTable Hive metadata for the table being scanned.
   * @param deserializerClass Class of the SerDe used to deserialize Writables read from Hadoop.
   * @param filterOpt If defined, then the filter is used to reject files contained in the data
   *                  directory being read. If None, then all files are accepted.
   */
  def makeRDDForTable(
      hiveTable: HiveTable,
      deserializerClass: Class[_ <: AbstractSerDe],
      filterOpt: Option[PathFilter]): RDD[_] =
  {
    assert(!hiveTable.isPartitioned, """makeRDDForTable() cannot be called on a partitioned table,
      since input formats may differ across partitions. Use makeRDDForTablePartitions() instead.""")

    // Create local references to member variables, so that the entire `this` object won't be
    // serialized in the closure below.
    val tableDesc = _tableDesc
    val broadcastedHiveConf = _broadcastedHiveConf

    val tablePath = hiveTable.getPath
    val inputPathStr = applyFilterIfNeeded(tablePath, filterOpt)

    //logDebug("Table input: %s".format(tablePath))
    val ifc = hiveTable.getInputFormatClass
      .asInstanceOf[java.lang.Class[InputFormat[Writable, Writable]]]
    val hadoopRDD = createHadoopRdd(tableDesc, inputPathStr, ifc)

    val deserializedHadoopRDD = hadoopRDD.mapPartitions { iter =>
      val hconf = broadcastedHiveConf.value.value
      val deserializer = deserializerClass.newInstance()
      deserializer.initialize(hconf, tableDesc.getProperties)

      // Deserialize each Writable to get the row value.
      iter.map {
        case v: Writable => deserializer.deserialize(v)
        case value =>
          sys.error(s"Unable to deserialize non-Writable: $value of ${value.getClass.getName}")
      }
    }
    deserializedHadoopRDD
  }

  override def makeRDDForPartitionedTable(partitions: Seq[HivePartition]): RDD[_] = {
    val partitionToDeserializer = partitions.map(part =>
      (part, part.getDeserializer.getClass.asInstanceOf[Class[AbstractSerDe]])).toMap
    makeRDDForPartitionedTable(partitionToDeserializer, filterOpt = None)
  }

  /**
   * Create a HadoopRDD for every partition key specified in the query. Note that for on-disk Hive
   * tables, a data directory is created for each partition corresponding to keys specified using
   * 'PARTITION BY'.
   *
   * @param partitionToDeserializer Mapping from a Hive Partition metadata object to the SerDe
   *     class to use to deserialize input Writables from the corresponding partition.
   * @param filterOpt If defined, then the filter is used to reject files contained in the data
   *     subdirectory of each partition being read. If None, then all files are accepted.
   */
  def makeRDDForPartitionedTable(
      partitionToDeserializer: Map[HivePartition, Class[_ <: AbstractSerDe]],
      filterOpt: Option[PathFilter]): RDD[_] =
  {
    val hivePartitionRDDs = partitionToDeserializer.map { case (partition, partDeserializer) =>
      val partDesc = Utilities.getPartitionDesc(partition)
      val partPath = partition.getPartitionPath
      val inputPathStr = applyFilterIfNeeded(partPath, filterOpt)
      val ifc = partDesc.getInputFileFormatClass
        .asInstanceOf[java.lang.Class[InputFormat[Writable, Writable]]]
      // Get partition field info
      val partSpec = partDesc.getPartSpec
      val partProps = partDesc.getProperties

      val partColsDelimited: String = partProps.getProperty(META_TABLE_PARTITION_COLUMNS)
      // Partitioning columns are delimited by "/"
      val partCols = partColsDelimited.trim().split("/").toSeq
      // 'partValues[i]' contains the value for the partitioning column at 'partCols[i]'.
      val partValues = if (partSpec == null) {
        Array.fill(partCols.size)(new String)
      } else {
        partCols.map(col => new String(partSpec.get(col))).toArray
      }

      // Create local references so that the outer object isn't serialized.
      val tableDesc = _tableDesc
      val broadcastedHiveConf = _broadcastedHiveConf
      val localDeserializer = partDeserializer

      val hivePartitionRDD = createHadoopRdd(tableDesc, inputPathStr, ifc)
      hivePartitionRDD.mapPartitions { iter =>
        val hconf = broadcastedHiveConf.value.value
        val rowWithPartArr = new Array[Object](2)
        // Map each tuple to a row object
        iter.map { value =>
          val deserializer = localDeserializer.newInstance()
          deserializer.initialize(hconf, partProps)
          val deserializedRow = deserializer.deserialize(value)
          rowWithPartArr.update(0, deserializedRow)
          rowWithPartArr.update(1, partValues)
          rowWithPartArr.asInstanceOf[Object]
        }
      }
    }.toSeq
    // Even if we don't use any partitions, we still need an empty RDD
    if (hivePartitionRDDs.size == 0) {
      new EmptyRDD[Object](SharkEnv.sc)
    } else {
      new UnionRDD(hivePartitionRDDs(0).context, hivePartitionRDDs)
    }
  }

  /**
   * If `filterOpt` is defined, then it will be used to filter files from `path`. These files are
   * returned in a single, comma-separated string.
   */
  private def applyFilterIfNeeded(path: Path, filterOpt: Option[PathFilter]): String = {
    filterOpt match {
      case Some(filter) =>
        val fs = path.getFileSystem(_localHConf)
        val filteredFiles = fs.listStatus(path, filter).map(_.getPath.toString)
        filteredFiles.mkString(",")
      case None => path.toString
    }
  }

  /**
   * Creates a HadoopRDD based on the broadcasted HiveConf and other job properties that will be
   * applied locally on each slave.
   */
  private def createHadoopRdd(
    tableDesc: TableDesc,
    path: String,
    inputFormatClass: Class[InputFormat[Writable, Writable]])
  : RDD[Writable] = {
    val initializeJobConfFunc = HadoopTableReader.initializeLocalJobConfFunc(path, tableDesc) _

    val rdd = new HadoopRDD(
      SharkEnv.sc,
      _broadcastedHiveConf.asInstanceOf[Broadcast[SerializableWritable[Configuration]]],
      Some(initializeJobConfFunc),
      inputFormatClass,
      classOf[Writable],
      classOf[Writable],
      _minSplitsPerRDD)

    // Only take the value (skip the key) because Hive works only with values.
    rdd.map(_._2)
  }

}

private[catalyst] object HadoopTableReader {

  /**
   * Curried. After given an argument for 'path', the resulting JobConf => Unit closure is used to
   * instantiate a HadoopRDD.
   */
  def initializeLocalJobConfFunc(path: String, tableDesc: TableDesc)(jobConf: JobConf) {
    FileInputFormat.setInputPaths(jobConf, path)
    if (tableDesc != null) {
      Utilities.copyTableJobPropertiesToConf(tableDesc, jobConf)
    }
    val bufferSize = System.getProperty("spark.buffer.size", "65536")
    jobConf.set("io.file.buffer.size", bufferSize)
  }
}

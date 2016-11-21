/* 
 * Copyright (c) 2015-2016 TU Delft, The Netherlands.
 * All rights reserved.
 * 
 * You can redistribute this file and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This file is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * Authors: Hamid Mushtaq
 *
*/
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf
import org.apache.log4j.Logger
import org.apache.log4j.Level
import java.io.File

import sys.process._
import scala.sys.process._
import scala.collection.JavaConversions._
import scala.io.Source
import org.apache.spark.rdd.{RDD,PairRDDFunctions}

import java.io._
import scala.collection.Seq

import tudelft.utils.ChromosomeRange
import tudelft.utils.DictParser
import tudelft.utils.Configuration
import tudelft.utils.SAMRecordIterator
import org.apache.spark.HashPartitioner
import collection.mutable.ArrayBuffer
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar

import htsjdk.samtools._

object DNASeqAnalyzer 
{
final val MemString = "-Xmx5120m" 
final val RefFileName = "ucsc.hg19.fasta"
final val SnpFileName = "dbsnp_138.hg19.vcf"
final val ExomeFileName = "gcat_set_025.bed"
//////////////////////////////////////////////////////////////////////////////
def getTimeStamp() : String =
{
    return "[" + new SimpleDateFormat("HH:mm:ss").format(Calendar.getInstance().getTime()) + "] "
}
def bwaRun (fileName: String, config: Configuration) : 
	Array[(Int, SAMRecord)] = 
{
	val numOfThreads = config.getNumThreads

	val toolsFolder = config.getToolsFolder
	val refFolder = config.getRefFolder
  val outputFolder = config.getOutputFolder

	val inFileName = config.getInputFolder + fileName
	val tempFileName = config.getTmpFolder + fileName + ".sam"
	val tempFile = new File(tempFileName)

  // Create output log writer
  val log = f"$outputFolder/log/bwa"
  new File(log).mkdirs
  val bw = new PrintWriter(new BufferedWriter(new FileWriter(f"$log/bwa_$fileName.log")), true)
  bw.println(f"$getTimeStamp\tStarted with bwa, fileName: $fileName")

	val command = Seq(toolsFolder + "/bwa", "mem", refFolder + RefFileName,"-p","-t", numOfThreads, inFileName) 
	(command #> tempFile).!
  bw.println(f"$getTimeStamp\t${command.mkString(" ")}")

	val bwaKeyValues = new BWAKeyValues(tempFileName)
	bwaKeyValues.parseSam()
	val kvPairs: Array[(Int, SAMRecord)] = bwaKeyValues.getKeyValuePairs()

	tempFile.delete
  bw.println(f"$getTimeStamp\tfinished bwaRun, fileName: $fileName")
	
	return kvPairs
}
	 
def writeToBAM(fileName: String, samRecordsSorted: Array[SAMRecord], config: Configuration) : ChromosomeRange = 
{
	val header = new SAMFileHeader()
	header.setSequenceDictionary(config.getDict())
	val outHeader = header.clone()
	outHeader.setSortOrder(SAMFileHeader.SortOrder.coordinate);
	val factory = new SAMFileWriterFactory();
	val writer = factory.makeBAMWriter(outHeader, true, new File(fileName));
	
	val r = new ChromosomeRange()
	val input = new SAMRecordIterator(samRecordsSorted, header, r)
	while(input.hasNext()) 
	{
		val sam = input.next()
		writer.addAlignment(sam);
	}
	writer.close();
	
	return r
}

  def variantCall(chrRegion: Int, samRecordsSorted: Array[SAMRecord], config: Configuration): Array[(Int, (Int, String))] = {
    val tmpFolder = config.getTmpFolder
    val toolsFolder = config.getToolsFolder
    val refFolder = config.getRefFolder
    val numOfThreads = config.getNumThreads
    val outputFolder = config.getOutputFolder
    val log = f"$outputFolder/log/vc"

    new File(log).mkdirs

    // Create output log writer
    val bw = new PrintWriter(new BufferedWriter(new FileWriter(f"$log/region$chrRegion.log")), true)
    bw.println(f"$getTimeStamp\tStarted with variantCall, chunck: $chrRegion")

    // SAM records are sorted by this point
    val chrRange = writeToBAM(s"$tmpFolder/region$chrRegion-p1.bam", samRecordsSorted, config)

    // Picard preprocessing
    val cmd1 = Seq( "java", MemString, "-jar", f"$toolsFolder/CleanSam.jar", 
                    f"INPUT=$tmpFolder/region$chrRegion-p1.bam", f"OUTPUT=$tmpFolder/region$chrRegion-p2.bam")
    bw.println(f"$getTimeStamp\t${cmd1.mkString(" ")}")
    cmd1.!

    val cmd2 = Seq( "java", MemString, "-jar", f"$toolsFolder/MarkDuplicates.jar", f"INPUT=$tmpFolder/region$chrRegion-p2.bam",
                    f"OUTPUT=$tmpFolder/region$chrRegion-p3.bam", f"METRICS_FILE=$tmpFolder/region$chrRegion-p3-metrics.txt")
    bw.println(f"$getTimeStamp\t${cmd2.mkString(" ")}")
    cmd2.!

    val cmd3 = Seq("java", MemString, "-jar", f"$toolsFolder/AddOrReplaceReadGroups.jar", f"INPUT=$tmpFolder/region$chrRegion-p3.bam",
                    f"OUTPUT=$tmpFolder/region$chrRegion.bam", "RGID=GROUP1", "RGLB=LIB1", "RGPL=ILLUMINA", "RGPU=UNIT1", "RGSM=SAMPLE1")
    bw.println(f"$getTimeStamp\t${cmd3.mkString(" ")}")
    cmd3.!

    val cmd4 = Seq("java", MemString, "-jar", f"$toolsFolder/BuildBamIndex.jar", f"INPUT=$tmpFolder/region$chrRegion.bam")
    bw.println(f"$getTimeStamp\t${cmd4.mkString(" ")}")
    cmd4.!

    // Remove the temporary files
    Seq("rm", f"$tmpFolder/region$chrRegion-p1.bam", f"$tmpFolder/region$chrRegion-p2.bam",
              f"$tmpFolder/region$chrRegion-p3.bam", f"$tmpFolder/region$chrRegion-p3-metrics.txt").!
    bw.println(f"$getTimeStamp\tremoved temporary files")

    // Make region file
    val tmpBedFile = new File(f"$tmpFolder/tmp$chrRegion.bed")
    chrRange.writeToBedRegionFile(tmpBedFile.getAbsolutePath())

    val cmd5 = Seq( f"$toolsFolder/bedtools", "intersect", "-a", 
                    f"$refFolder/$ExomeFileName", "-b", f"$tmpFolder/tmp$chrRegion.bed", "-header")
    val outputBedFile = new File(f"$tmpFolder/bed$chrRegion.bed")
    (cmd5 #> outputBedFile).!
    bw.println(f"$getTimeStamp\t${cmd5.mkString(" ")}")
    tmpBedFile.delete

    // Indel Realignment
    val cmd6 = Seq( "java", MemString, "-jar", f"$toolsFolder/GenomeAnalysisTK.jar", "-T", "RealignerTargetCreator", "-nt", 
                    numOfThreads, "-R", f"$refFolder/$RefFileName", "-I", f"$tmpFolder/region$chrRegion.bam", "-o", 
                    f"$tmpFolder/region$chrRegion.intervals", "-L", f"$tmpFolder/bed$chrRegion.bed")
    bw.println(f"$getTimeStamp\t${cmd6.mkString(" ")}")
    cmd6.!

    val cmd7 = Seq( "java", MemString, "-jar", f"$toolsFolder/GenomeAnalysisTK.jar", "-T", "IndelRealigner", "-R", 
                    f"$refFolder/$RefFileName", "-I", f"$tmpFolder/region$chrRegion.bam", "-targetIntervals",
                    f"$tmpFolder/region$chrRegion.intervals", "-o", f"$tmpFolder/region$chrRegion-2.bam", "-L", 
                    f"$tmpFolder/bed$chrRegion.bed")
    bw.println(f"$getTimeStamp\t${cmd7.mkString(" ")}")
    cmd7.!

    // Remove the temporary files
    Seq("rm", f"$tmpFolder/region$chrRegion.bam", f"$tmpFolder/region$chrRegion.bai", f"$tmpFolder/region$chrRegion.intervals").!
    bw.println(f"$getTimeStamp\tremove temporary files")

    // Base quality recalibration
    val cmd8 = Seq( "java", MemString, "-jar", s"$toolsFolder/GenomeAnalysisTK.jar", "-T", "BaseRecalibrator", "-nct", 
                    numOfThreads, "-R", f"$refFolder/$RefFileName", "-I", f"$tmpFolder/region$chrRegion-2.bam", "-o",
                    f"$tmpFolder/region$chrRegion.table", "-L", f"$tmpFolder/bed$chrRegion.bed",
                    "--disable_auto_index_creation_and_locking_when_reading_rods", "-knownSites", f"$refFolder/$SnpFileName")
    bw.println(f"$getTimeStamp\t${cmd8.mkString(" ")}")
    cmd8.!

    val cmd9 = Seq( "java", MemString, "-jar", f"$toolsFolder/GenomeAnalysisTK.jar", "-T", "PrintReads", 
                    "-R", f"$refFolder/$RefFileName", "-I", f"$tmpFolder/region$chrRegion-2.bam", "-o",
                    f"$tmpFolder/region$chrRegion-3.bam", "-BQSR",
                    f"$tmpFolder/region$chrRegion.table", "-L", f"$tmpFolder/bed$chrRegion.bed")
    bw.println(f"$getTimeStamp\t${cmd9.mkString(" ")}")
    cmd9.!

    // Remove the temporary files
    Seq("rm", f"$tmpFolder/region$chrRegion-2.bam", f"$tmpFolder/region$chrRegion-2.bai", f"$tmpFolder/region$chrRegion.table").!
    bw.println(f"$getTimeStamp\tremove temporary files")

    // Haplotype -> Uses the region bed file
    val cmd10 = Seq("java", MemString, "-jar", f"$toolsFolder/GenomeAnalysisTK.jar", "-T", "HaplotypeCaller", "-nct", 
                    numOfThreads, "-R", f"$refFolder/$RefFileName", "-I", f"$tmpFolder/region$chrRegion-3.bam", "-o",
                    f"$tmpFolder/region$chrRegion.vcf", "-stand_call_conf", "30.0", "-stand_emit_conf", "30.0", "-L", 
                    f"$tmpFolder/bed$chrRegion.bed", "--no_cmdline_in_header", 
                    "--disable_auto_index_creation_and_locking_when_reading_rods")
    bw.println(f"$getTimeStamp\t${cmd10.mkString(" ")}")
    cmd10.!
    bw.println(f"$getTimeStamp\tVcf file created")

    // Remove the temporary files
    Seq("rm", f"$tmpFolder/region$chrRegion-3.bam", f"$tmpFolder/region$chrRegion-3.bai", 
              f"$tmpFolder/region$chrRegion.vcf.idx", f"$tmpFolder/bed$chrRegion.bed").!
    bw.println(f"$getTimeStamp\tremove temporary files")
    bw.close

    val vcfPath = f"$tmpFolder/region$chrRegion.vcf"
    // Get the lines of the vcf file, 
    // and remove the line that start with #, because this indicates a comment
    val vcfContainer = Source .fromFile(vcfPath)
                              .getLines
                              .filter(!_.startsWith("#"))
    // The vcf file is no longer needed, remove it
    Seq("rm", vcfPath).!
    /**
     *  From each line get the position and the chromosome number
     *  Human chromosomes are numbered from 1 to 22 and X, Y
     *  For our calcualtions x is converted to 23 and Y is converted to 24
     */
    vcfContainer.map { line =>
                        val lineArray = line.split("\t")
                        // The second word contains the position
                        val position = lineArray(1).toInt
                        // The first three characters of the first word contain the chromosome number
                        val chrNum = lineArray(0).substring(3) match {
                          case "X" => 23
                          case "Y" => 24
                          case num => num.toInt
                        }

                        (chrNum, (position, line))
                      }
                      .toArray
  }
/**
 *  The records are load balanced over the given number of partitions as follows:
 *    First, the number of records (size) per chromosome number gets computed and
 *    and sorted in a descending order with the size as key.
 *    Second, this array gets iterated, and each element is assigned to a partition with the 
 *    least number of records i.e. the chromosome numbers are assigned from the largest size
 *    to the smallest size to the partitions with the least number of records.
 */

def loadBalance(rdd:RDD[(Int, SAMRecord)], numPartitions:Int): RDD[(Int, SAMRecord)] = {
    // First cache the rdd to avoid recomputation
    rdd.cache()
    // Get the number of records for each chromosome number and sort it in a descending order
    val sizeChromosomes = rdd .map{case (chromosomeNumber,_) => (chromosomeNumber, 1)}
                              .reduceByKey((a,b) => a + b)
                            .sortBy(x => x._2, false)
                            .collect()
    // This array saves the currently number of assigned records per partiotion                       
    var recordsPerPartition = Array.fill(numPartitions)(0)
    // This array saves the partition number of each chromosome number
    var partitionOfChromosomes = Array.fill(sizeChromosomes.length)(0)
    // Iterate through sizeChromosomes
    for(i <- 0 until sizeChromosomes.length){
        // Get the chromosomeNumber, size
        val (chromosomeNumber, size) = sizeChromosomes(i)
        // Get the partition with the smallest number of records
        val smallestPartition = recordsPerPartition.indexOf(recordsPerPartition.min)
        // Assign the chromosome number to this partition
        partitionOfChromosomes(chromosomeNumber-1) = smallestPartition
        // Update the number of records partition
        recordsPerPartition(smallestPartition) += size
    }

    // Add the partition number to each record according to its chromosome number
    rdd .map{ case  (chromosomeNumber, sizeSamRecords) => 
                    (partitionOfChromosomes(chromosomeNumber-1), (chromosomeNumber, sizeSamRecords)) }
        // Partiton the records by the assigned partition number per chromosome number
        .partitionBy(new HashPartitioner(numPartitions))
        // Remove the partition number to return in the same type as the input rdd
        .map{ case  (_, (chromosomeNumber, sizeSamRecords)) => 
                    (chromosomeNumber, sizeSamRecords)}
}

def main(args: Array[String]) 
{
	val config = new Configuration()
	config.initialize()
		 
	val conf = new SparkConf().setAppName("DNASeqAnalyzer")
	// For local mode, include the following two lines
	conf.setMaster("local[" + config.getNumInstances() + "]")
	conf.set("spark.cores.max", config.getNumInstances())
	
	val sc = new SparkContext(conf)
	
	// Comment these two lines if you want to see more verbose messages from Spark
	Logger.getLogger("org").setLevel(Level.OFF);
	Logger.getLogger("akka").setLevel(Level.OFF);
		
	var t0 = System.currentTimeMillis
	
	// Get the path of the relevent folders
	val numInstances = config.getNumInstances.toInt
	val inputFolder = config.getInputFolder

	// Create the output folder if it does not exist
  val outputFolder = config.getOutputFolder
	new File(outputFolder).mkdirs

  // Create temp folder if it does not exist
  val temFolder = config.getTmpFolder
  new File(temFolder).mkdirs

	// Get the list of the files in input folder
	val chunkDir = new File(inputFolder)	
	val chunkNames = chunkDir	.listFiles
								.filter(x => x.isFile)
								.map(x => x.getName)
								.toList
	// Broadcast spark context to all executors
	val configBroadcast = sc.broadcast(config)

	// Create rdd with record type: <chromosome number, SAM record>
	val bwaStep = sc.parallelize(chunkNames)
					.flatMap(fileName => bwaRun(fileName, configBroadcast.value))

  // The records are load balanced over the given number of partitions
  val loadBalancedRdd = loadBalance(bwaStep, numInstances)
  /**
   *  For each chromosoom region the SAM records are sorted by position:
   *      First the SAM records are compared by their chromosome numbers.
   *      If the chromosome numbers are the same, their starting positions are compared
   *  Variant calling is performed on each of these sorted SAM recores
   */
	val variantStep = loadBalancedRdd.mapPartitionsWithIndex {
						        case (chrRegion, iterator) =>
						          val samRecordsSorted = iterator .toArray
                              						            .sortWith {
                              						              case ((chr1,samRecord1), (chr2,samRecord2)) =>
                              						                if (chr1 == chr2) {
                              						                	samRecord1.getAlignmentStart < samRecord2.getAlignmentStart
                              						                }
                              						                else chr1 < chr2
                              						            }
                              						            .map{ case(_, sam) => sam}
						         variantCall(chrRegion, samRecordsSorted, config).toIterator
							}
  // Create file to write output
	val file = new File(outputFolder + "results.vcf")
	val bw = new PrintWriter(new BufferedWriter(new FileWriter(file)), true)

  // Write each line to file
  variantStep	.sortBy { case (chromoNumber, (startPosition, line)) => (chromoNumber, startPosition) }
						  .map { case (_, (_, line)) => line }
						  .collect
					   .foreach(line => bw.println(line))
	bw.close()



	val et = (System.currentTimeMillis - t0) / 1000 
	println("|Execution time: %d mins %d secs|".format(et/60, et%60))
}
//////////////////////////////////////////////////////////////////////////////
} // End of Class definition

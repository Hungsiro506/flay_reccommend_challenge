/**
  * Created by hungdv on 08/08/2017.
  */

import org.apache.log4j.{Level, Logger}

import scala.collection.Map
import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import scala.Tuple3
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.SparkContext._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.mllib.recommendation._
import org.apache.spark.rdd.RDD
import org.apache.spark.mllib.recommendation.MatrixFactorizationModel
import org.apache.spark.sql.SparkSession

object GridSearch {
  def main(args: Array[String]): Unit = {
    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("akka").setLevel(Level.OFF)

    val sparkSession = SparkSession.builder()
      .appName("tunning CF - ALS model")
      .master("local[16]").getOrCreate()
    val now = System.nanoTime
    val datasetPath = "/user/hungvd8/vinh_parse_2017-08-02/*.csv"
    println("LOAD RAW DATA")
    val rawUserMoviesData = sparkSession.sparkContext.textFile(datasetPath)
    println("MY RATINGs")
    val myRatings = buildMyRating(rawUserMoviesData)
    myRatings.cache()
    println("USER MAPPING")
    val userIDToInt :Predef.Map[String,Int] = userIDMapping(myRatings).map{x => (x._1,x._2.toInt)}.collect().toMap
    //val userIDToInt = userIDMapping(myRatings)
    println("MOVIE MAPPING")
    val movieIDToInt : Predef.Map[String,Int] = movieIDMapping(myRatings).map{x => (x._1,x._2.toInt)}.collect().toMap
    //val movieIDToInt = movieIDMapping(myRatings)

    val bUserIDToInt = sparkSession.sparkContext.broadcast(userIDToInt)
    val bMovieIDToInt = sparkSession.sparkContext.broadcast(movieIDToInt)

    val dataTransf = buildRatings(myRatings,bUserIDToInt,bMovieIDToInt)
    //userIDToInt.unpersist()
    //movieIDToInt.unpersist()
    myRatings.unpersist()
    println("EVALUE -----")
    evaluate(sparkSession.sparkContext,dataTransf)
    val micros = (System.nanoTime - now) / 1000
    println("Executioni time : %d microseconds".format(micros))
  }
  def buildMyRating(rawUserMoviesData: RDD[String]) = {
    val data = rawUserMoviesData.map { line =>

      val rt = line.split(",") match
      {
        case Array(userID, movieID, count) => MyRating(userID, movieID, safetyParserStringToIn(count,0))
        case _ => MyRating("n/a","n/a",0)
      }
      rt
    }.filter(x => x.userID != "n/a" )
    data
  }
  def toInt(s:String): Option[Int]={
    try{
      Some(s.toInt)
    }
    catch {
      case e:NumberFormatException => None
    }
  }
  def safetyParserStringToIn(aString:String,returnValue:Int) ={
    toInt(aString) match{
      case Some(n) => n
      case None => returnValue
    }
  }

  def userIDMapping(data: RDD[MyRating]) = {
    val userIDToInt = data.map(_.userID).distinct().zipWithUniqueId()
    userIDToInt
  }

  def userIDReverseMapping(userIDToInt: RDD[(String,Long)]) = {
    val reverseMappingUser = userIDToInt.map { case (l, r) => (r, l) }
  }

  def movieIDMapping(data: RDD[MyRating]) = {
    val movieIDToInt = data.map(_.movieID).distinct().zipWithUniqueId()
    movieIDToInt
  }

  def movieIDReverserMapping(movieIDToInt : RDD[(String,Long)]) = {
    val reverseMappingMovie = movieIDToInt.map { case (l, r) => (r, l) }
  }

  def buildRatings(data : RDD[MyRating],
                   userIDToInt:Broadcast[Predef.Map[String,Int]] ,
                  movieIDToInt : Broadcast[Predef.Map[String,Int]]) = {
    val ratings = data.map{ r =>
      Rating(userIDToInt.value(r.userID),movieIDToInt.value(r.movieID),r.rating)
      //Rating(userIDToInt.value.lookup(r.userID).head.toInt,movieIDToInt.value.lookup(r.movieID).head.toInt,r.rating)
    }
    ratings
  }

  def areaUnderCurve(
                      positiveData: RDD[Rating],
                      bAllItemIDs: Broadcast[Array[Int]],
                      predictFunction: (RDD[(Int,Int)] => RDD[Rating])) = {
    // What this actually computes is AUC, per user. The result is actually something
    // that might be called "mean AUC".

    // Take held-out data as the "positive", and map to tuples
    val positiveUserProducts = positiveData.map(r => (r.user, r.product))
    // Make predictions for each of them, including a numeric score, and gather by user
    val positivePredictions = predictFunction(positiveUserProducts).groupBy(_.user)

    // BinaryClassificationMetrics.areaUnderROC is not used here since there are really lots of
    // small AUC problems, and it would be inefficient, when a direct computation is available.

    // Create a set of "negative" products for each user. These are randomly chosen
    // from among all of the other items, excluding those that are "positive" for the user.
    val negativeUserProducts = positiveUserProducts.groupByKey().mapPartitions {
      // mapPartitions operates on many (user,positive-items) pairs at once
      userIDAndPosItemIDs => {
        // Init an RNG and the item IDs set once for partition
        val random = new Random()
        val allItemIDs = bAllItemIDs.value
        userIDAndPosItemIDs.map { case (userID, posItemIDs) =>
          val posItemIDSet = posItemIDs.toSet
          val negative = new ArrayBuffer[Int]()
          var i = 0
          // Keep about as many negative examples per user as positive.
          // Duplicates are OK
          while (i < allItemIDs.size && negative.size < posItemIDSet.size) {
            val itemID = allItemIDs(random.nextInt(allItemIDs.size))
            if (!posItemIDSet.contains(itemID)) {
              negative += itemID
            }
            i += 1
          }
          // Result is a collection of (user,negative-item) tuples
          negative.map(itemID => (userID, itemID))
        }
      }
    }.flatMap(t => t)
    // flatMap breaks the collections above down into one big set of tuples

    // Make predictions on the rest:
    val negativePredictions = predictFunction(negativeUserProducts).groupBy(_.user)

    // Join positive and negative by user
    positivePredictions.join(negativePredictions).values.map {
      case (positiveRatings, negativeRatings) =>
        // AUC may be viewed as the probability that a random positive item scores
        // higher than a random negative one. Here the proportion of all positive-negative
        // pairs that are correctly ranked is computed. The result is equal to the AUC metric.
        var correct = 0L
        var total = 0L
        // For each pairing,
        for (positive <- positiveRatings;
             negative <- negativeRatings) {
          // Count the correctly-ranked pairs
          if (positive.rating > negative.rating) {
            correct += 1
          }
          total += 1
        }
        // Return AUC: fraction of pairs ranked correctly
        correct.toDouble / total
    }.mean() // Return mean AUC over users
  }

  def evaluate(
                sc: SparkContext,
                data: RDD[Rating]
               ): Unit = {

    val Array(trainData, cvData) = data.randomSplit(Array(0.9, 0.1))
    trainData.cache()
    cvData.cache()

    val allItemIDs = data.map(_.product).distinct().collect()
    val bAllItemIDs = sc.broadcast(allItemIDs)

 /*   val mostListenedAUC = areaUnderCurve(cvData, bAllItemIDs, predictMostListened(sc, trainData))
    println(mostListenedAUC)*/
    sc.setCheckpointDir("/data/kafka/checkpoint/rm")
    val evaluations =
      /*for (iter   <- Array(5,   50) ;
           rank   <- Array(10,  50);
           lambda <- Array(1.0, 0.0001);
           alpha  <- Array(1.0, 40.0))*/
      /*for (iter   <- 10 to 30 by 5 ;
           rank   <- 10 to 50 by 10;
           lambda <- Array(1.5,1.3,1.1,1.0,0.9,0.5,0.1, 0.0001);
           alpha  <- 5.0 to  40.0 by 5) */
        for (iter   <- Array(40,41,42,43,50) ;
             rank   <- Array(10,6,5,4,3);
             lambda <- Array(1.5,1.4,1.3,1.2);
             alpha  <- Array(40,41,42))

        yield {
          val model = ALS.trainImplicit(trainData, rank, iter, lambda, alpha)
          val auc = areaUnderCurve(cvData, bAllItemIDs, model.predict)
          unpersist(model)
          //println(((iter,rank, lambda, alpha), auc))
          ((iter,rank, lambda, alpha), auc)
        }

    evaluations.sortBy(_._2).reverse.foreach(println)

    trainData.unpersist()
    cvData.unpersist()
  }


  def model(sparkSession: SparkSession,
            rawUserMoviesData: RDD[String]): Unit = {
    //val trainingData = buildRatings(rawUserMoviesData)

  }

  /**
    * Unpersist model
    * @param model
    */
  def unpersist(model: MatrixFactorizationModel): Unit = {
    // At the moment, it's necessary to manually unpersist the RDDs inside the model
    // when done with it in order to make sure they are promptly uncached
    model.userFeatures.unpersist()
    model.productFeatures.unpersist()
  }


}

case class MyRating(userID: String, movieID: String, rating: Int) extends Serializable {

}

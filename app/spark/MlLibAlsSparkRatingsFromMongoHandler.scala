package spark

import javax.inject.{Inject, Singleton}

import org.apache.spark.mllib.recommendation.{ALS, MatrixFactorizationModel, Rating}
import org.apache.spark.rdd.RDD
import play.api.Configuration
/**
  * Created by P. Akhmedzianov on 03.03.2016.
  */
@Singleton
class MlLibAlsSparkRatingsFromMongoHandler @Inject() (val configuration: Configuration)
  extends SparkRatingsFromMongoHandler with java.io.Serializable{

  val configurationPath_ = configuration.getString(
    "mlLibAlsSparkRatingsFromMongoHandler.configurationPath").getOrElse(
    SparkAlsPropertiesLoader.defaultPath)
  val ratingsCollectionName_ = configuration.getString("mongodb.ratingsCollectionName")
    .getOrElse(RATINGS_DEFAULT_COLLECTION_NAME)
  val usersCollectionName_ = configuration.getString("mongodb.usersCollectionName")
    .getOrElse(USERS_DEFAULT_COLLECTION_NAME)

  var alsConfigurationOption_ : Option[AlsConfiguration] = None

  var matrixFactorizationModelOption_ : Option[MatrixFactorizationModel] = None

  var trainRatingsRddOption_ : Option[RDD[Rating]] = None
  var validationRatingsRddOption_ : Option[RDD[Rating]] = None
  var testRatingsRddOption_ : Option[RDD[Rating]] = None

  def test(): Unit = {
    matrixFactorizationModelOption_ match {
      case Some(matrixModel) =>
        println("Train RMSE = " + getRmseForRdd(trainRatingsRddOption_.get))
        println("Validation RMSE = " + getRmseForRdd(validationRatingsRddOption_.get))
        println("Test MAP@K = " + getMeanAveragePrecisionForRdd(testRatingsRddOption_.get))
      case None => throw new IllegalStateException()
    }
  }

  def initialize(isTuningParameters: Boolean, isInitializingValidationRdd: Boolean,
                 isInitializingTestRdd: Boolean, isFilteringInput:Boolean): Unit = {
    if (isTuningParameters && !isInitializingValidationRdd){
      throw new IllegalArgumentException
    }
    else {
      alsConfigurationOption_ = Some(SparkAlsPropertiesLoader.loadFromDisk(configurationPath_))
      initializeRdds(filterInputByNumberOfKeyEntriesRdd(getKeyValueRatings(
        getCollectionFromMongoRdd(ratingsCollectionName_)),10),
        isInitializingValidationRdd, isInitializingTestRdd)
      if (isTuningParameters) tuneHyperParametersWithGridSearch()
      initializeModelWithRdd(trainRatingsRddOption_.get)
    }
  }

  def updateRecommendationsInMongo(isTuning : Boolean): Unit ={
    initialize(isTuning, isTuning == true, isInitializingTestRdd = false,
      isFilteringInput = true)
    initializeModelWithAllData()
    exportAllPredictionsToMongo(5)
  }

  private def tuneHyperParametersWithGridSearch(): Unit = {
    if(alsConfigurationOption_.isDefined && trainRatingsRddOption_.isDefined &&
      validationRatingsRddOption_.isDefined){
      matrixFactorizationModelOption_ = Some(ALS.train(trainRatingsRddOption_.get, alsConfigurationOption_.get.rank_,
        alsConfigurationOption_.get.numIterations_, alsConfigurationOption_.get.lambda_))
      var currentBestValidationRmse = getRmseForRdd(validationRatingsRddOption_.get)

      for (rank <- alsConfigurationOption_.get.ranksList_;
           lambda <- alsConfigurationOption_.get.lambdasList_;
           numberOfIterations <- alsConfigurationOption_.get.numbersOfIterationsList_) {
        matrixFactorizationModelOption_ = Some(ALS.train(trainRatingsRddOption_.get, rank, numberOfIterations, lambda))
        val validationRmse = getRmseForRdd(validationRatingsRddOption_.get)
        println("RMSE (validation) = " + validationRmse + " for the model trained with rank = "
          + rank + ", lambda = " + lambda + ", and numIter = " + numberOfIterations + ".")
        if (validationRmse < currentBestValidationRmse) {
          currentBestValidationRmse = validationRmse
          alsConfigurationOption_ = Some(alsConfigurationOption_.get.copy(rank_ = rank,
             numIterations_ = numberOfIterations, lambda_ = lambda ))
        }
      }
      println("The best model was trained with rank = " + alsConfigurationOption_.get.rank_ +
        " and lambda = " + alsConfigurationOption_.get.lambda_ + ", and numIter = " +
        alsConfigurationOption_.get.numIterations_ + ", and its RMSE on the test set is "
        + currentBestValidationRmse + ".")
      SparkAlsPropertiesLoader.saveToDisk(configurationPath_, alsConfigurationOption_.get)
    }
    else{
      throw new IllegalStateException()
    }
  }

  private def initializeModelWithRdd(ratingsRdd: RDD[Rating]) = {
    if(alsConfigurationOption_.isDefined) {
      matrixFactorizationModelOption_ = Some(ALS.train(ratingsRdd, alsConfigurationOption_.get.rank_,
        alsConfigurationOption_.get.numIterations_, alsConfigurationOption_.get.lambda_))
    } else{
      throw new IllegalStateException()
    }
  }

  private def initializeModelWithAllData(): Unit ={
    if(trainRatingsRddOption_.isDefined && alsConfigurationOption_.isDefined){
      var unitedRdd = trainRatingsRddOption_.get
      if(validationRatingsRddOption_.isDefined) unitedRdd = unitedRdd.union(validationRatingsRddOption_.get)
      if(testRatingsRddOption_.isDefined) unitedRdd = unitedRdd.union(testRatingsRddOption_.get)
      matrixFactorizationModelOption_ = Some(ALS.train(unitedRdd, alsConfigurationOption_.get.rank_,
        alsConfigurationOption_.get.numIterations_, alsConfigurationOption_.get.lambda_))
    }
    else{
      throw new IllegalStateException()
    }
  }



  private def initializeRdds(filteredInputRdd: RDD[(Int, (Int, Double))], isInitializingValidationRdd: Boolean,
                     isInitializingTestRdd: Boolean): Unit = {
    if ((isInitializingValidationRdd || isInitializingTestRdd) && alsConfigurationOption_.isDefined) {
      val gropedByKeyRdd = filteredInputRdd.groupByKey()

      val trainRdd = gropedByKeyRdd.map { groupedLine =>
        val movieRatePairsArray = groupedLine._2.toArray
        (groupedLine._1, movieRatePairsArray.splitAt(Math.ceil(
          alsConfigurationOption_.get.trainingShareInArraysOfRates_ * movieRatePairsArray.length).toInt)._1)
      }.flatMapValues(x => x)

      trainRatingsRddOption_ = Some(transformToRatingRdd(trainRdd))

      val afterSubtractionRdd = filteredInputRdd.subtract(trainRdd)
      if (isInitializingValidationRdd && isInitializingTestRdd) {
        val validateAndTestRdds = afterSubtractionRdd.randomSplit(
          Array(alsConfigurationOption_.get.validationShareAfterSubtractionTraining_,
            1 - alsConfigurationOption_.get.validationShareAfterSubtractionTraining_))
        validationRatingsRddOption_ = Some(transformToRatingRdd(validateAndTestRdds(0)))
        testRatingsRddOption_ = Some(transformToRatingRdd(validateAndTestRdds(1)))
      }
      else if (isInitializingValidationRdd) {
        validationRatingsRddOption_ = Some(transformToRatingRdd(afterSubtractionRdd))
      }
      else {
        testRatingsRddOption_ = Some(transformToRatingRdd(afterSubtractionRdd))
      }
    }
    else {
      trainRatingsRddOption_ = Some(transformToRatingRdd(filteredInputRdd))
    }
  }


  private def getRmseForRdd(ratings: RDD[Rating]): Double = {
    // Evaluate the model on rating data
    if(matrixFactorizationModelOption_.isDefined) {
      val usersProducts = ratings.map { case Rating(user, product, rate) =>
        (user, product)
      }
      val predictions =
        matrixFactorizationModelOption_.get.predict(usersProducts).map { case Rating(user, product, rate) =>
          ((user, product), rate)
        }
      val ratesAndPreds = ratings.map { case Rating(user, product, rate) =>
        ((user, product), rate)
      }.join(predictions)
      val meanSquaredError = ratesAndPreds.map { case ((user, product), (r1, r2)) =>
        val err = (r1 - r2)
        err * err
      }.mean()
      Math.sqrt(meanSquaredError)
    }
    else{
      throw new IllegalStateException()
    }
  }

  private def getMeanAveragePrecisionForRdd(ratings: RDD[Rating]): Double = {
    if(matrixFactorizationModelOption_.isDefined) {
      // Evaluate the model on rating data
      val K = 10
      val usersWithPositiveRatings = ratings.filter { case Rating(user, product, rate) => (rate > 5) }.map {
        case Rating(user, product, rate) =>
          (user, product)
      }.groupByKey()
      val predictions = matrixFactorizationModelOption_.get.recommendProductsForUsers(K).map {
        case (userId, ratingPredictions) => (userId, ratingPredictions.map {
          case Rating(user, product, rate) => product
        })
      }
      val trueBooksWithPositiveRateAndPredictedBooks = usersWithPositiveRatings.join(predictions)
      val meanAveragePrecision = trueBooksWithPositiveRateAndPredictedBooks.map { case (user, (trueBooks, predictedBooks)) =>
        val trueBooksArray = trueBooks.toArray
        val minNumberOfTrueRatesAndK = Math.min(trueBooksArray.length, K)
        var precision = 0
        for (k <- 0 to predictedBooks.length - 1) {
          val addition = if (trueBooksArray.contains(predictedBooks(k))) k else 0
          precision += addition
        }
        precision.toDouble / minNumberOfTrueRatesAndK
      }.mean()
      meanAveragePrecision
    }
    else{
      throw new IllegalStateException()
    }
  }

  private def exportAllPredictionsToMongo(numberOfRecommendations:Int): Unit = {
    if(matrixFactorizationModelOption_.isDefined) {
      val predictionsRdd  = matrixFactorizationModelOption_.get.recommendProductsForUsers(
        numberOfRecommendations).mapValues{arrayRatings => arrayRatings.map{
        case Rating(userId, bookId, rate) => bookId
      }}
      updateMongoCollectionWithRdd(usersCollectionName_,
        predictionsRdd.map { resTuple =>
          (new Object, getMongoUpdateWritableFromIdValueTuple[Int, Array[Int]](resTuple, "_id",
            "personalRecommendations"))
        })
    }
  }

  def filterInputByNumberOfKeyEntriesRdd(inputRdd: RDD[(Int, (Int, Double))],
                                         threshold: Int): RDD[(Int, (Int, Double))] = {
    val filteredRatingsRdd = inputRdd.groupByKey().filter { groupedLine =>
      groupedLine._2.size >= threshold
    }.flatMapValues(x => x)
    filteredRatingsRdd
  }


  def predict(user: Int, product: Int): Double = {
    matrixFactorizationModelOption_ match {
      case Some(matrixModel) =>
        matrixModel.predict(user, product)
      case None => throw new IllegalStateException()
    }
  }

  def recommendProducts(user: Int, num: Int): Map[Int, Double] = {
    matrixFactorizationModelOption_ match {
      case Some(matrixModel) =>
        def resFromModel = matrixModel.recommendProducts(user, num)
        def res = resFromModel.map(rate => (rate.product -> rate.rating)).toMap
        res
      case None => throw new IllegalStateException()
    }
  }
}

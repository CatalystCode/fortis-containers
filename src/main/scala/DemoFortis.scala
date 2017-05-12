import com.github.catalystcode.fortis.spark.streaming.facebook.{FacebookAuth, FacebookUtils}
import com.github.catalystcode.fortis.spark.streaming.instagram.dto.InstagramItem
import com.microsoft.partnercatalyst.fortis.spark.streamfactories.{InstagramLocationStreamFactory, InstagramTagStreamFactory, TwitterStreamFactory}
import com.microsoft.partnercatalyst.fortis.spark.streamprovider.{ConnectorConfig, StreamProvider}
import com.microsoft.partnercatalyst.fortis.spark.transforms.{Analysis, AnalyzedItem}
import com.microsoft.partnercatalyst.fortis.spark.transforms.image.{ImageAnalysisAuth, ImageAnalyzer}
import com.microsoft.partnercatalyst.fortis.spark.transforms.language.{LanguageDetector, LanguageDetectorAuth}
import com.microsoft.partnercatalyst.fortis.spark.transforms.locations.client.FeatureServiceClient
import com.microsoft.partnercatalyst.fortis.spark.transforms.locations.nlp.PlaceRecognizer
import com.microsoft.partnercatalyst.fortis.spark.transforms.locations.{Geofence, LocationsExtractor}
import com.microsoft.partnercatalyst.fortis.spark.transforms.sentiment.{SentimentDetector, SentimentDetectorAuth}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.{SparkConf, SparkContext}
import twitter4j.{Status => TwitterStatus}

object DemoFortis {
  def main(args: Array[String]) {
    val mode = args.headOption.getOrElse("")
    if (mode.isEmpty) {
      System.err.println("Please specify a mode")
      System.exit(1)
    }

    val conf = new SparkConf().setAppName("Simple Application")
    val sc = new SparkContext(conf)
    val ssc = new StreamingContext(sc, Seconds(1))

    val streamProvider = StreamProvider()
      .withFactories(
        List(
          new InstagramLocationStreamFactory,
          new InstagramTagStreamFactory)
        )
      .withFactories(
        List(
          new TwitterStreamFactory
        )
      )

    val streamRegistry = buildRegistry()

    Logger.getLogger("org").setLevel(Level.ERROR)
    Logger.getLogger("akka").setLevel(Level.ERROR)
    Logger.getLogger("libinstagram").setLevel(Level.DEBUG)
    Logger.getLogger("libfacebook").setLevel(Level.DEBUG)
    Logger.getLogger("liblocations").setLevel(Level.DEBUG)

    val geofence = Geofence(north = 49.6185146245, west = -124.9578052195, south = 46.8691952854, east = -121.0945042053)  // useful tool to get fences for testing: http://boundingbox.klokantech.com
    val placeRecognizer = new PlaceRecognizer(Option(System.getenv("FORTIS_MODELS_DIRECTORY")))
    val featureServiceClient = new FeatureServiceClient("localhost:8080")
    val locationsExtractor = new LocationsExtractor(featureServiceClient, geofence, Some(placeRecognizer)).buildLookup()
    val imageAnalysis = new ImageAnalyzer(ImageAnalysisAuth(System.getenv("OXFORD_VISION_TOKEN")), featureServiceClient)
    val languageDetection = new LanguageDetector(LanguageDetectorAuth(System.getenv("OXFORD_LANGUAGE_TOKEN")))
    val sentimentDetection = new SentimentDetector(SentimentDetectorAuth(System.getenv("OXFORD_LANGUAGE_TOKEN")))


    val facebookAuth = FacebookAuth(accessToken = System.getenv("FACEBOOK_AUTH_TOKEN"), appId = System.getenv("FACEBOOK_APP_ID"), appSecret = System.getenv("FACEBOOK_APP_SECRET"))
    if (mode.contains("instagram")) {
      streamProvider.buildStream[InstagramItem](ssc, streamRegistry("instagram")) match {
        case Some(stream) => stream
          .map(instagram => {
            // do computer vision analysis: keyword extraction, etc.
            val source = instagram.link
            val analysis = imageAnalysis.analyze(instagram.images.standard_resolution.url)
            AnalyzedItem(originalItem = instagram, analysis = analysis, source = source)
          })
          .map(analyzedInstagram => {
            // map tagged locations to location features
            var analyzed = analyzedInstagram
            val instagram = analyzed.originalItem
            if (instagram.location.isDefined) {
              val location = instagram.location.get
              val sharedLocations = locationsExtractor.fetch(latitude = location.latitude, longitude = location.longitude).toList
              analyzed = analyzed.copy(sharedLocations = sharedLocations ++ analyzed.sharedLocations)
            }
            analyzed
          })
          .map(x => s"${x.source} --> ${x.analysis.locations.mkString(",")}").print(20)
        case None => println("No streams were configured for 'instagram' pipeline.")
      }
    }

    if (mode.contains("twitter")) {
      streamProvider.buildStream[TwitterStatus](ssc, streamRegistry("twitter")) match {
        case Some(stream) => stream
          .map(tweet => {
            val source = s"https://twitter.com/statuses/${tweet.getId}"
            val language = if (Option(tweet.getLang).isDefined) { Option(tweet.getLang) } else { languageDetection.detectLanguage(tweet.getText) }
            val analysis = Analysis(language = language)
            AnalyzedItem(originalItem = tweet, analysis = analysis, source = source)
          })
          .map(analyzedPost => {
            // sentiment detection
            val text = analyzedPost.originalItem.getText
            val language = analyzedPost.analysis.language.getOrElse("")
            val inferredSentiment = sentimentDetection.detectSentiment(text, language).map(List(_)).getOrElse(List())
            analyzedPost.copy(analysis = analyzedPost.analysis.copy(sentiments = inferredSentiment ++ analyzedPost.analysis.sentiments))
          })
          .map(analyzedTweet => {
            // map tagged locations to location features
            var analyzed = analyzedTweet
            val location = analyzed.originalItem.getGeoLocation
            if (location != null) {
              val lat = location.getLatitude
              val lng = location.getLongitude
              val sharedLocations = locationsExtractor.fetch(latitude = lat, longitude = lng).toList
              analyzed = analyzed.copy(sharedLocations = sharedLocations ++ analyzed.sharedLocations)
            }
            analyzed
          })
          .map(analyzedTweet => {
            // infer locations from text
            val inferredLocations = locationsExtractor.analyze(analyzedTweet.originalItem.getText, analyzedTweet.analysis.language).toList
            analyzedTweet.copy(analysis = analyzedTweet.analysis.copy(locations = inferredLocations ++ analyzedTweet.analysis.locations))
          })
          .map(x => s"${x.source} --> ${x.analysis.locations.mkString(",")}").print(20)
        case None => println("No streams were configured for 'twitter' pipeline.")
      }
    }

    if (mode.contains("facebook")) {
      val facebookStream = FacebookUtils.createPageStream(ssc, facebookAuth, "aljazeera")

      facebookStream
        .map(post => {
          val source = post.post.getPermalinkUrl.toString
          val language = languageDetection.detectLanguage(post.post.getMessage)
          val analysis = Analysis(language = language)
          AnalyzedItem(originalItem = post, analysis = analysis, source = source)
        })
        .map(analyzedPost => {
          // sentiment detection
          val text = analyzedPost.originalItem.post.getMessage
          val language = analyzedPost.analysis.language.getOrElse("")
          val inferredSentiment = sentimentDetection.detectSentiment(text, language).map(List(_)).getOrElse(List())
          println(s"${text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')}\t${inferredSentiment.mkString}")
          analyzedPost.copy(analysis = analyzedPost.analysis.copy(sentiments = inferredSentiment ++ analyzedPost.analysis.sentiments))
        })
        .map(analyzedPost => {
          // map tagged locations to location features
          var analyzed = analyzedPost
          val place = Option(analyzed.originalItem.post.getPlace)
          val location = if (place.isDefined) { Some(place.get.getLocation) } else { None }
          if (location.isDefined) {
            val lat = location.get.getLatitude
            val lng = location.get.getLongitude
            val sharedLocations = locationsExtractor.fetch(latitude = lat, longitude = lng).toList
            analyzed = analyzed.copy(sharedLocations = sharedLocations ++ analyzed.sharedLocations)
          }
          analyzed
        })
        .map(analyzedPost => {
          // infer locations from text
          val inferredLocations = locationsExtractor.analyze(analyzedPost.originalItem.post.getMessage, analyzedPost.analysis.language).toList
          analyzedPost.copy(analysis = analyzedPost.analysis.copy(locations = inferredLocations ++ analyzedPost.analysis.locations))
        })
        .map(x => s"${x.source} --> ${x.analysis.locations.mkString(",")}").print(20)
    }

    ssc.start()
    ssc.awaitTerminationOrTimeout(Seconds(60).milliseconds)
  }

  /**
    * Build connector config registry from hard-coded values for demo.
    *
    * The key is the name of the pipeline and the value is a list of connector configs whose streams should comprise it.
    */
  private def buildRegistry() : Map[String, List[ConnectorConfig]] = {
    Map[String, List[ConnectorConfig]](
      "instagram" -> List(
        ConnectorConfig(
          "InstagramTag",
          Map(
            "authToken" -> System.getenv("INSTAGRAM_AUTH_TOKEN"),
            "tag" -> "rose"
          )
        ),
        ConnectorConfig(
          "InstagramLocation",
          Map(
            "authToken" -> System.getenv("INSTAGRAM_AUTH_TOKEN"),
            "latitude" -> "49.25",
            "longitude" -> "-123.1"
          )
        )
      ),
      "twitter" -> List(
        ConnectorConfig(
          "Twitter",
          Map(
            "consumerKey" -> System.getenv("TWITTER_CONSUMER_KEY"),
            "consumerSecret" -> System.getenv("TWITTER_CONSUMER_SECRET"),
            "accessToken" -> System.getenv("TWITTER_ACCESS_TOKEN"),
            "accessTokenSecret" -> System.getenv("TWITTER_ACCESS_TOKEN_SECRET")
          )
        )
      )
    )
  }
}

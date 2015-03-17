package controllers

import java.net.URI
import java.net.URLEncoder.encode

import scala.util.Try

import play.api.mvc._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import org.joda.time.{DateTime, Duration}

import lib.elasticsearch.{MetadataSearchResults, ElasticSearch, SearchResults}
import lib.{Notifications, Config, S3Client}
import lib.querysyntax.{Condition, Parser}
import com.gu.mediaservice.lib.auth
import com.gu.mediaservice.lib.auth.KeyStore
import com.gu.mediaservice.lib.argo.ArgoHelpers
import com.gu.mediaservice.lib.formatting.parseDateFromQuery
import com.gu.mediaservice.api.Transformers


object MediaApi extends Controller with ArgoHelpers {

  val keyStore = new KeyStore(Config.keyStoreBucket, Config.awsCredentials)

  val commonTransformers = new Transformers(Config.services)

  val rootUri = Config.rootUri
  val cropperUri = Config.cropperUri
  val loaderUri = Config.loaderUri
  val metadataUri = Config.metadataUri
  val kahunaUri = Config.kahunaUri
  val imgoptsUri = Config.imgoptsUri

  def index = Action {
    val searchParams = List("q", "ids", "offset", "length", "fromDate", "toDate",
      "orderBy", "since", "until", "uploadedBy", "archived", "valid", "free",
      "hasExports", "hasIdentifier", "missingIdentifier", "hasMetadata").mkString(",")

    val response = Json.obj(
      "data"  -> Json.obj("description" -> "This is the Media API"),
      "links" -> Json.arr(
        Json.obj("rel" -> "search", "href" -> s"$rootUri/images{?$searchParams}"),
        Json.obj("rel" -> "image",  "href" -> s"$rootUri/images/{id}"),
        Json.obj("rel" -> "metadata-search", "href" -> s"$rootUri/images/metadata/{field}{?q}"),
        Json.obj("rel" -> "cropper", "href" -> cropperUri),
        Json.obj("rel" -> "loader", "href" -> loaderUri),
        Json.obj("rel" -> "metadata", "href" -> metadataUri),
        Json.obj("rel" -> "session", "href" -> s"$kahunaUri/session")
      )
    )
    Ok(response).as(ArgoMediaType)
  }

  val Authenticated = auth.Authenticated(keyStore, Config.kahunaUri)

  def getImage(id: String) = Authenticated.async { request =>
    val params = GeneralParams(request)
    ElasticSearch.getImageById(id) map {
      case Some(source) => Ok(imageResponse(params)(id, source)).as(ArgoMediaType)
      case None         => NotFound.as(ArgoMediaType)
    }
  }

  def getImageFileMetadata(id: String) = Authenticated.async { request =>
    val params = GeneralParams(request)
    ElasticSearch.getImageById(id) map {
      case Some(source) => Ok(Json.obj("data" -> source \ "fileMetadata")).as(ArgoMediaType)
      case None         => NotFound.as(ArgoMediaType)
    }
  }

  def deleteImage(id: String) = Authenticated {
    Notifications.publish(Json.obj("id" -> id), "delete-image")
    Accepted.as(ArgoMediaType)
  }


  def imageSearch = Authenticated.async { request =>
    val params = GeneralParams(request)
    val searchParams = SearchParams(request)
    ElasticSearch.search(searchParams) map { case SearchResults(hits, totalCount) =>
      val images = hits map (imageResponse(params) _).tupled
      Ok(Json.obj(
        "offset" -> searchParams.offset,
        "length" -> images.size,
        "total"  -> totalCount,
        "data"   -> images
      )).as(ArgoMediaType)
    }
  }

  def imageResponse(params: GeneralParams)(id: String, source: JsValue): JsValue =
    if (params.showSecureUrl) {
      // Round expiration time to try and hit the cache as much as possible
      // TODO: do we really need these expiration tokens? they kill our ability to cache...
      val expiration = roundDateTime(DateTime.now, Duration.standardMinutes(10)).plusMinutes(20)
      val fileUri = new URI((source \ "source" \ "file").as[String])
      val secureUrl = S3Client.signUrl(Config.imageBucket, fileUri, expiration)
      val secureThumbUrl = S3Client.signUrl(Config.thumbBucket, fileUri, expiration)

      val creditField = (source \ "metadata" \ "credit").as[Option[String]]
      val sourceField = (source \ "metadata" \ "source").as[Option[String]]
      // TODO: This might be easier to get from the `SearchParams`
      // downfall: it might give the wrong value if a bug is introduced
      val valid = Config.requiredMetadata.forall { field =>
        (source \ "metadata" \ field).asOpt[String].isDefined
      }

      val image = source.transform(transformers.addSecureSourceUrl(secureUrl))
        .flatMap(_.transform(transformers.addSecureThumbUrl(secureThumbUrl)))
        .flatMap(_.transform(transformers.removeFileData))
        .flatMap(_.transform(transformers.addFileMetadataUrl(s"$rootUri/images/$id/fileMetadata")))
        .flatMap(_.transform(transformers.wrapUserMetadata(id)))
        .flatMap(_.transform(transformers.addValidity(valid)))
        .flatMap(_.transform(transformers.addUsageCost(creditField, sourceField))).get

      // FIXME: don't hardcode paths from other APIs - once we're
      // storing a copy of the data in the DB, we can use it to point
      // to the right place
      val links = List(
        Json.obj("rel" -> "crops",    "href" -> s"$cropperUri/crops/$id"),
        Json.obj("rel" -> "metadata", "href" -> s"$metadataUri/metadata/$id"),
        Json.obj("rel" -> "optimised", "href" -> makeImgoptsUri(new URI(secureUrl)))
      )
      Json.obj("uri" -> s"$rootUri/images/$id", "data" -> image, "links" -> links)
    }
    else source
  // TODO: always add most transformers even if no showSecureUrl

  object transformers {

    def addUsageCost(credit: Option[String], source: Option[String]): Reads[JsObject] =
      __.json.update(__.read[JsObject].map(_ ++ Json.obj("cost" -> ImageExtras.getCost(credit, source))))

    def removeFileData: Reads[JsObject] =
      (__ \ "fileMetadata").json.prune

    def addFileMetadataUrl(url: String): Reads[JsObject] =
      __.json.update(__.read[JsObject].map (_ ++ Json.obj("fileMetadata" -> Json.obj("uri" -> url))))

    // FIXME: tidier way to replace a key in a JsObject?
    def wrapUserMetadata(id: String): Reads[JsObject] =
      __.read[JsObject].map { root =>
        val userMetadata = commonTransformers.objectOrEmpty(root \ "userMetadata")
        val wrappedUserMetadata = userMetadata.transform(commonTransformers.wrapAllMetadata(id)).get
        root ++ Json.obj("userMetadata" -> wrappedUserMetadata)
      }

    def addSecureSourceUrl(url: String): Reads[JsObject] =
      (__ \ "source").json.update(__.read[JsObject].map(_ ++ Json.obj("secureUrl" -> url)))

    def addSecureThumbUrl(url: String): Reads[JsObject] =
      (__ \ "thumbnail").json.update(__.read[JsObject].map (_ ++ Json.obj("secureUrl" -> url)))

    def addValidity(valid: Boolean): Reads[JsObject] =
      __.json.update(__.read[JsObject]).map(_ ++ Json.obj("valid" -> valid))
  }

  def makeImgoptsUri(uri: URI): String =
    imgoptsUri + List(uri.getPath, uri.getRawQuery).mkString("?") + "{&w,h,q}"

  def roundDateTime(t: DateTime, d: Duration) = {
    t minus (t.getMillis - (t.getMillis.toDouble / d.getMillis).round * d.getMillis)
  }

  // TODO: work with analysed fields
  def metadataSearch(field: String, q: Option[String]) = Authenticated.async { request =>
    ElasticSearch.metadataSearch(MetadataSearchParams(field, q)) map { case MetadataSearchResults(results, total) =>
      // TODO: Add some useful links
      Ok(Json.obj(
        "length"-> total,
        "data" -> Json.toJson(results)
      )).as(ArgoMediaType)
    }
  }
}


case class GeneralParams(showSecureUrl: Boolean)

object GeneralParams {

  def apply(request: Request[Any]): GeneralParams =
    GeneralParams(request.getQueryString("show-secure-url") forall (_.toBoolean))

}

case class SearchParams(
  query: Option[String],
  structuredQuery: List[Condition],
  ids: Option[List[String]],
  offset: Int,
  length: Int,
  orderBy: Option[String],
  fromDate: Option[DateTime],
  toDate: Option[DateTime],
  archived: Option[Boolean],
  hasExports: Option[Boolean],
  hasIdentifier: Option[String],
  missingIdentifier: Option[String],
  valid: Option[Boolean],
  free: Option[Boolean],
  uploadedBy: Option[String],
  labels: List[String],
  hasMetadata: List[String]
)

object SearchParams {

  def apply(request: Request[Any]): SearchParams = {

    def commaSep(key: String): List[String] = request.getQueryString(key).toList.flatMap(_.trim.split(','))

    val query = request.getQueryString("q")
    val structuredQuery = query.map(Parser.run) getOrElse List()

    SearchParams(
      query,
      structuredQuery,
      request.getQueryString("ids").map(_.split(",").toList),
      request.getQueryString("offset") flatMap (s => Try(s.toInt).toOption) getOrElse 0,
      request.getQueryString("length") flatMap (s => Try(s.toInt).toOption) getOrElse 10,
      request.getQueryString("orderBy") orElse request.getQueryString("sortBy"),
      request.getQueryString("fromDate") orElse request.getQueryString("since") flatMap parseDateFromQuery,
      request.getQueryString("toDate") orElse request.getQueryString("until") flatMap parseDateFromQuery,
      request.getQueryString("archived").map(_.toBoolean),
      request.getQueryString("hasExports").map(_.toBoolean),
      request.getQueryString("hasIdentifier"),
      request.getQueryString("missingIdentifier"),
      request.getQueryString("valid").map(_.toBoolean),
      request.getQueryString("free").map(_.toBoolean),
      request.getQueryString("uploadedBy"),
      request.getQueryString("labels").map(_.toString.split(",").toList) getOrElse List(),
      commaSep("hasMetadata")
    )
  }

}

case class MetadataSearchParams(field: String, q: Option[String])

// Default to pay for now
object ImageExtras {
  def getCost(credit: Option[String], source: Option[String]) = {
    val freeCredit   = credit.exists(isFreeCredit)
    val freeSource   = source.exists(isFreeSource)
    val payingSource = source.exists(isPaySource)
    if ((freeCredit || freeSource) && ! payingSource) "free"
    else "pay"
  }

  private def isFreeCredit(credit: String) = Config.freeCreditList.exists(f => f.toLowerCase == credit.toLowerCase)
  private def isFreeSource(source: String) = Config.freeSourceList.exists(f => f.toLowerCase == source.toLowerCase)
  private def isPaySource(source: String)  = Config.payGettySourceList.exists(f => f.toLowerCase == source.toLowerCase)
}

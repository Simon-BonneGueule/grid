import com.gu.mediaservice.lib.elasticsearch6.ElasticSearch6Config
import com.gu.mediaservice.lib.play.GridComponents
import controllers.{HealthCheck, ThrallController}
import lib._
import play.api.ApplicationLoader.Context
import router.Routes

class ThrallComponents(context: Context) extends GridComponents(context) {
  final override lazy val config = new ThrallConfig(configuration)

  final override val buildInfo = utils.buildinfo.BuildInfo

  val store = new ThrallStore(config)
  val metadataEditorNotifications = new MetadataEditorNotifications(config)
  val thrallMetrics = new ThrallMetrics(config)

  val es6Config = ElasticSearch6Config(
    alias = config.writeAlias,
    url = config.elasticsearch6Url,
    cluster = config.elasticsearch6Cluster,
    shards = config.elasticsearch6Shards,
    replicas = config.elasticsearch6Replicas
  )

  val es6 = new ElasticSearch6(es6Config, Some(thrallMetrics))
  es6.ensureAliasAssigned()

  val thrallKinesisMessageConsumer = new kinesis.ThrallMessageConsumer(
    config, es6, thrallMetrics, store, metadataEditorNotifications, new SyndicationRightsOps(es6), config.from
  )
  thrallKinesisMessageConsumer.start()

  val thrallController = new ThrallController(controllerComponents)
  val healthCheckController = new HealthCheck(es6, thrallKinesisMessageConsumer, config, controllerComponents)

  override lazy val router = new Routes(httpErrorHandler, thrallController, healthCheckController, management)
}

package functional

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import models.{ApiKey, ServiceGroupIdentifier, Webhook}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.Json

import scala.concurrent.duration._

class AlertAndAnalyticsSpec(name: String, configurationSpec: => Configuration)
    extends OtoroshiSpec {

  lazy val serviceHost = "analytics.oto.tools"
  implicit val system  = ActorSystem("otoroshi-test")

  override def getTestConfiguration(configuration: Configuration) = Configuration(
    ConfigFactory
      .parseString(s"""
                      |{
                      |  app.analyticsWindow = 1
                      |}
       """.stripMargin)
      .resolve()
  ).withFallback(configurationSpec).withFallback(configuration)

  s"[$name] Otoroshi Alerts and Analytics module" should {

    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().futureValue // WARM UP
    }

    "produce alerts when admin api are modified" in {

      val counter = new AtomicInteger(0)
      val server  = new AlertServer(counter).await()

      val apiKey = ApiKey(
        clientId = "apikey-monthly",
        clientSecret = "1234",
        clientName = "apikey-test",
        authorizedEntities = Seq(ServiceGroupIdentifier("default"))
      )

      val config = (for {
        config <- getOtoroshiConfig()
        newConfig = config.copy(
          alertsWebhooks = Seq(
            Webhook(
              url = s"http://127.0.0.1:${server.port}/events"
            )
          )
        )
        _ <- updateOtoroshiConfig(newConfig)
      } yield config).futureValue

      awaitF(6.seconds).futureValue

      createOtoroshiApiKey(apiKey).futureValue
      deleteOtoroshiApiKey(apiKey).futureValue

      awaitF(2.seconds).futureValue

      counter.get() >= 2 mustBe true

      updateOtoroshiConfig(config).futureValue

      awaitF(6.seconds).futureValue

      server.stop()
    }

    "produce analytics events for everything" in {
      val counter = new AtomicInteger(0)
      val server  = new AnalyticsServer(counter).await()

      val apiKey = ApiKey(
        clientId = "apikey-monthly",
        clientSecret = "1234",
        clientName = "apikey-test",
        authorizedEntities = Seq(ServiceGroupIdentifier("default"))
      )

      val config = (for {
        config <- getOtoroshiConfig()
        newConfig = config.copy(
          analyticsWebhooks = Seq(
            Webhook(
              url = s"http://127.0.0.1:${server.port}/events"
            )
          )
        )
        _ <- updateOtoroshiConfig(newConfig)
      } yield config).futureValue

      awaitF(6.seconds).futureValue

      getOtoroshiConfig().futureValue
      getOtoroshiApiKeys().futureValue
      getOtoroshiServiceGroups().futureValue
      getOtoroshiServices().futureValue
      createOtoroshiApiKey(apiKey).futureValue
      deleteOtoroshiApiKey(apiKey).futureValue

      await(2.seconds)

      counter.get() >= 16 && counter.get() <= 21 mustBe true

      updateOtoroshiConfig(config).futureValue

      awaitF(6.seconds).futureValue

      server.stop()
    }

    "stop servers" in {
      system.terminate()
    }

    "shutdown" in {
      stopAll()
    }
  }
}

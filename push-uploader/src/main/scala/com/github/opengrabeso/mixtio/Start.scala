package com.github.opengrabeso.mixtio

import java.awt.Desktop
import java.net.{URL, URLEncoder}
import java.util.concurrent.CountDownLatch

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.coding._
import akka.http.scaladsl.model.ContentType.WithCharset
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{ActorMaterializer, BindFailedException}
import akka.util.ByteString

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise, duration}
import scala.util.{Success, Try}
import scala.xml.Elem
import java.time.{ZoneId, ZonedDateTime}

import common.Util._
import shared.Digest

import scala.util.control.NonFatal

object Start extends App {

  case class AuthData(userId: String, since: ZonedDateTime, sessionId: String)

  private val instanceId = System.currentTimeMillis()
  private var authData = Option.empty[AuthData]
  private val authDone = new CountDownLatch(1)

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  object HttpHandlerHelper {

    implicit class Prefixed(responseXml: Elem) {
      def prefixed: String = {
        val prefix = """<?xml version="1.0" encoding="UTF-8"?>""" + "\n"
        prefix + responseXml.toString
      }
    }

    private def sendResponseWithContentType(code: Int, response: String, ct: WithCharset) = {
      HttpResponse(status = code, entity = HttpEntity(ct, response))
    }

    def sendResponseHtml(code: Int, response: Elem): HttpResponse = {
      sendResponseWithContentType(code, response.toString, ContentTypes.`text/html(UTF-8)`)
    }

    def sendResponseXml(code: Int, responseXml: Elem): HttpResponse = {
      sendResponseWithContentType(code, responseXml.prefixed, ContentTypes.`text/xml(UTF-8)`)
    }
    def sendResponseBytes(code: Int, response: Array[Byte]): HttpResponse = {
      val ct = ContentTypes.`application/octet-stream`
      HttpResponse(status = code, entity = HttpEntity(ct, response))
    }
  }

  import HttpHandlerHelper._

  private val serverPort = 8088 // do not use 8080, would conflict with Google App Engine Dev Server

  private case class ServerInfo(system: ActorSystem, binding: Future[ServerBinding]) {
    def stop(): Unit = {
      implicit val executionContext = system.dispatcher
      binding.flatMap(_.unbind()) // trigger unbinding from the port
    }

    def shutdown(): Unit = {
      implicit val executionContext = system.dispatcher
      binding
        .flatMap(_.unbind()) // trigger unbinding from the port
        .onComplete(_ => system.terminate()) // and shutdown when done

    }

  }

  private def shutdownAnotherInstance() = {
    import scala.concurrent.ExecutionContext.Implicits.global

    val localServerUrl = s"http://localhost:$serverPort/shutdown?id=$instanceId"

    val localRequest = Http().singleRequest(HttpRequest(uri = localServerUrl)).map(_.discardEntityBytes())

    // try communicating with the local Stravimat, if not responding, use the remote one
    Try(Await.result(localRequest, Duration(2000, duration.MILLISECONDS)))
  }

  trait ServerUsed {
    def url: String
    def desciption: String
  }
  // GAE local server
  object ServerLocal8080 extends ServerUsed {
    def url = "http://localhost:8080"
    def desciption = " to local server"
  }
  // Jetty embedded server
  object ServerLocal4567 extends ServerUsed {
    def url = "http://localhost:4567"
    def desciption = " to local Jetty server"
  }
  // production server
  object ServerProduction extends ServerUsed {
    def url = "https://mixtio.appspot.com"
    override def desciption = ""
  }

  val server: ServerUsed = {
    import scala.concurrent.ExecutionContext.Implicits.global

    val localTest = true // disabling the local test would make uploads faster for used of the production build (no need to wait for the probe timeout)
    val localFound = Promise[ServerUsed]()

    def localServerConfirmed(confirmed: ServerUsed): Unit = synchronized {
      println(s"Confirmed local server ${confirmed.url}")
      if (!localFound.tryComplete(Success(confirmed))) {
        // we always use only the first server confirmed
        // a developer should not run both
        println("Warning: it seems there are two local servers running")
      }
    }

    def tryLocalServer(s: ServerUsed) = {
      Http().singleRequest(HttpRequest(uri = s.url + "/ping")).map(_.discardEntityBytes()).map(_ => localServerConfirmed(s))
    }

    if (localTest) {
      tryLocalServer(ServerLocal8080)
      tryLocalServer(ServerLocal4567)
    }

    // try communicating with the local Stravimat, if not responding, use the remote one
    Try(Await.result(localFound.future, Duration(2000, duration.MILLISECONDS))).getOrElse(ServerProduction)
  }

  private val stravimatUrl = server.url

  private object Tray {
    import java.awt._
    import javax.swing._
    import java.awt.event._

    private var state: String = ""

    private def showImpl() = {
      assert(SwingUtilities.isEventDispatchThread)
      import javax.imageio.ImageIO
      // https://docs.oracle.com/javase/7/docs/api/java/awt/SystemTray.html

      if (SystemTray.isSupported) {
        try {
          UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName)
        } catch {
          case _: Exception =>
        }

        val tray = SystemTray.getSystemTray
        val iconSize = tray.getTrayIconSize
        val imageFile = if ((iconSize.height max iconSize.width) > 16) "/stravaUpload32.png" else "/stravaUpload16.png"
        val is = getClass.getResourceAsStream(imageFile)

        val image = ImageIO.read(is)
        val imageSized = image.getScaledInstance(iconSize.width, iconSize.height, Image.SCALE_SMOOTH)


        val trayIcon = new TrayIcon(imageSized, appName)

        import java.awt.event.MouseAdapter

        val popup = new JPopupMenu

        def addItem(title: String, action: => Unit) = {
          val listener = new ActionListener() {
            def actionPerformed(e: ActionEvent) = action
          }
          val item = new JMenuItem(title)
          item.addActionListener(listener)
          popup.add(item)
        }

        addItem("Login...", startBrowser())
        popup.addSeparator()
        addItem("Exit", doShutdown())


        trayIcon addMouseListener new MouseAdapter {
          override def mouseReleased(e: MouseEvent) = maybeShowPopup(e)

          override def mousePressed(e: MouseEvent) = maybeShowPopup(e)

          def maybeShowPopup(e: MouseEvent) = {
            if (e.isPopupTrigger) {
              popup.setLocation(e.getX, e.getY)
              popup.setInvoker(popup)
              popup.setVisible(true)
            }
          }
        }

        try {
          tray.add(trayIcon)
        } catch  {
          case e: AWTException =>
            e.printStackTrace()
        }
        Some(trayIcon)
      } else {
        None
      }
    }

    private def removeImpl(icon: TrayIcon): Unit = {
      assert(SwingUtilities.isEventDispatchThread)
      if (SystemTray.isSupported) {
        val tray = SystemTray.getSystemTray
        tray.remove(icon)
      }
    }

    private def changeStateImpl(icon: TrayIcon, s: String): Unit = {
      assert(SwingUtilities.isEventDispatchThread)
      state = s
      val title = appName
      val text = if (state.isEmpty) title else title + ": " + state
      icon.setToolTip(text)
    }

    private def loginDoneImpl(icon: TrayIcon): Unit = {
      icon.setPopupMenu(null)
    }


    def show(): Option[TrayIcon] = {
      val p = Promise[Option[TrayIcon]]
      SwingUtilities.invokeLater(new Runnable {
        override def run() = p.success(showImpl())
      })
      Await.result(p.future, Duration.Inf)
    }

    def remove(icon: TrayIcon): Unit = {
      SwingUtilities.invokeAndWait(new Runnable {
        override def run() = removeImpl(icon)
      })
    }

    def loginDone(icon: TrayIcon): Unit = {
      SwingUtilities.invokeAndWait(new Runnable {
        override def run() = loginDoneImpl(icon)
      })
    }

    def changeState(icon: TrayIcon, s: String): Unit = {
      SwingUtilities.invokeLater(new Runnable {
        override def run() = changeStateImpl(icon, s)
      })
    }
  }

  private def startBrowser() = {
    /*
    Authentication dance
    - request Stravimat to perform authentication, including user selection
     - http://stravimat/push-start?port=<XXXX>
    - Stravimat knowns or gets the Strava auth token (user id hash)
    - it generates a Stravimat token and sends it back by calling http://localhost:<XXXX>/auth?token=<ttttttttttt>
     - this is captured by authHandler
    - we receive the token and redirect to a page http://stravimat/push-push?token=<XXXX>
    */
    val sessionId = System.currentTimeMillis()
    val startPushUrl = s"$stravimatUrl/push-start?port=$serverPort&session=$sessionId"
    println(s"Starting browser $startPushUrl")
    Desktop.getDesktop.browse(new URL(startPushUrl).toURI)
  }


  def authHandler(userId: String, since: String, sessionId: String) = {
    // session is authorized, we can continue sending the data
    serverInfo.stop()
    println(s"Auth done - $appName user id $userId, session $sessionId")
    val sinceTime = ZonedDateTime.parse(since)
    authData = Some(AuthData(userId, sinceTime, sessionId))
    authDone.countDown()
    val doPushUrl = s"$stravimatUrl/push-do"
    redirect(doPushUrl, StatusCodes.Found)
  }

  private def doShutdown(): Unit = {
    serverInfo.shutdown()
    authDone.countDown()
  }

  def shutdownHandler(id: Long): HttpResponse = {
    val response = if (id !=instanceId) {
      println(s"Shutdown - stop server $instanceId, received $id")
      doShutdown()
      <result>Done</result>
    } else {
      println(s"Shutdown ignored - same instance")
      <result>Ignored - same instance</result>
    }

    sendResponseXml(200, response)
  }

  private def startHttpServer(callbackPort: Int): ServerInfo = {

    import scala.concurrent.ExecutionContext.Implicits.global

    val requests = path("auth") {
      parameters('user, 'since, 'session) { (user, since, session) =>
        authHandler(user, since, session)
      }
    } ~ path("shutdown") {
      parameter('id) {
        id =>
          complete(shutdownHandler(id.toLong))
      }
    }

    val route = get(requests)

    def startIt() = {
      Http().bindAndHandle(Route.handlerFlow(route), "localhost", callbackPort)
    }

    val bindingFuture: Future[ServerBinding] = startIt().recoverWith {
      case _: BindFailedException =>
        // an old hanging instance of this app may be already running, request it to shut down
        shutdownAnotherInstance()
        startIt()
    }

    println(s"Auth server $instanceId started, listening on http://localhost:$callbackPort")
    // TODO: we may never receive oauth answer, the session may be terminated or there may be an error
    // we should time-out gracefully in such case, as not to block the server port
    // currently we at least let the new server shut down us so that instances are not multiplying
    ServerInfo(system, bindingFuture)
  }

  private def waitForServerToStop(serverInfo: ServerInfo) = {
    try {
      val _ = Await.result(serverInfo.binding, Duration.Inf)
      authDone.await()

    } catch {
      case NonFatal(_) =>
        println("Server not started")
        serverInfo.system.terminate()
    }
  }

  def performUpload(data: AuthData) = {

    val AuthData(userId, since, sessionId) = data

    val sinceDate = since minusDays 1

    import scala.concurrent.ExecutionContext.Implicits.global

    val listFiles = MoveslinkFiles.listFiles.toList
    // sort files by timestamp
    val wantedFiles = listFiles.filter(MoveslinkFiles.timestampFromName(_).forall(_ > sinceDate))

    reportProgress(wantedFiles.size)

    val sortedFiles = wantedFiles.sortBy(MoveslinkFiles.timestampFromName)

    val localTimeZone = ZoneId.systemDefault.toString

    val requestParams = s"user=$userId&timezone=${URLEncoder.encode(localTimeZone, "UTF-8")}"

    val sessionCookie = headers.Cookie("sessionid", sessionId) // we might want to set this as HTTP only - does it matter?
    val useGzip = true // !useLocal
    // do not use encoding headers, as we want to encode / decoce on own own
    // this was done to keep payload small as a workaround for https://issuetracker.google.com/issues/63371955
    val gzipCustom = true
    val encodingHeader = if (useGzip && !gzipCustom) Some(headers.`Content-Encoding`(HttpEncodings.gzip)) else None
    val contentType = if (useGzip && gzipCustom) ContentTypes.`application/octet-stream` else ContentTypes.`text/plain(UTF-8)` // it is XML in fact, but not fully conformant

    val req = Http().singleRequest(
      HttpRequest(
        uri = s"$stravimatUrl/push-put-start?$requestParams&total-files=${sortedFiles.size}",
        method = HttpMethods.POST,
        headers = List(sessionCookie)
      )
    ).map { resp =>
      resp.discardEntityBytes()
    }

    Await.result(req, Duration.Inf)

    val reqs = for {
      f <- sortedFiles
      fileBytes <- MoveslinkFiles.get(f)
    } yield {
      // consider async processing here - a few requests in parallel could improve throughput
      val digest = Digest.digest(fileBytes)

      // it seems production App Engine already decodes gziped request body, but development one does not
      // as I do not see any clean way how to indicate the development server it should do its own decoding
      // I do no use GZip on development server as a workaround
      def gzipEncoded(bytes: Array[Byte]) = if (useGzip) Gzip.encode(ByteString(bytes)) else ByteString(bytes)

      val req = Http().singleRequest(
        HttpRequest(
          uri = s"$stravimatUrl/push-put-digest?$requestParams&path=$f",
          method = HttpMethods.POST,
          headers = List(sessionCookie),
          entity = HttpEntity(digest)
        )
      ).flatMap { resp =>
        resp.discardEntityBytes()
        println(s"File status $f = ${resp.status}")
        resp.status match {
          case StatusCodes.NoContent =>
            Future.successful(())
          case StatusCodes.OK =>
            // digest not matching, we need to send full content
            val bodyBytes = gzipEncoded(fileBytes)
            val uploadReq = Http().singleRequest(
              HttpRequest(
                uri = s"$stravimatUrl/push-put?$requestParams&path=$f&digest=$digest",
                method = HttpMethods.POST,
                headers = List(sessionCookie) ++ encodingHeader,
                entity = HttpEntity(contentType, bodyBytes)
              )
            ).map { resp =>
              resp.discardEntityBytes()
              resp.status
            }
            println(s"  Upload started: $f ${fileBytes.length.toByteSize} -> ${bodyBytes.length.toByteSize}")
            uploadReq.map { status =>
              println(s"    Async upload $f status $status")
              status
            }

          case _ => // unexpected - what to do?
            Future.successful(())
        }
      }
      f -> req
    }

    reportProgress(reqs.size)

    reqs.zipWithIndex.foreach { case ((f, r), i) =>
      Await.result(r, Duration.Inf)
      // TODO: handle upload failures somehow
      println(s"  Await upload $f status $r")
      reportProgress(reqs.size + 1 - i)
    }

    serverInfo.system.terminate()

  }

  val icon = Tray.show()

  def reportProgress(i: Int): Unit = {
    def state = s"Uploading $i files" + server.desciption
    icon.foreach(Tray.changeState(_, state))
  }

  private val serverInfo = startHttpServer(serverPort)

  startBrowser()

  waitForServerToStop(serverInfo)
  // server is stopped once auth information is received into authUserId, or when another instance has forced a shutdown

  icon.foreach(Tray.loginDone)

  for (data <- authData) {
    performUpload(data)
  }

  Await.result(serverInfo.system.whenTerminated, Duration.Inf)
  println("System stopped")
  icon.foreach(Tray.remove)
}

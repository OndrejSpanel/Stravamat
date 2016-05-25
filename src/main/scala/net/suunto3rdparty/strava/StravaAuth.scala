package net.suunto3rdparty.strava

import java.awt.Desktop
import java.io.IOException
import java.net.{InetSocketAddress, URL}
import java.util.concurrent._

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}
import scala.util.Try
import scala.xml.Elem

object StravaAuth {
  private val callbackPath = "stravaAuth.html"
  private val statusPath = "status.xml"
  private val donePath = "done.xml"
  private val pollPeriod = 2000 // miliseconds

  private def paramPattern(param: String) = ("/.*\\?.*" + param + "=([^&\\?]*).*").r

  private val passedPattern = paramPattern("code")
  private val errorPattern = paramPattern("error")
  private val statePattern = paramPattern("state")

  sealed trait ServerEvent
  object ServerStatusSent extends ServerEvent
  object ServerDoneSent extends ServerEvent
  object WindowClosedSent extends ServerEvent

  private case class ServerShutdown(server: HttpServer, executor: ExecutorService, events: LinkedBlockingQueue[ServerEvent])
  private val authResult = Promise[String]()
  private var server: Option[ServerShutdown] = None

  private var reportProgress: String = "Reading files..."

  private var reportResult: String = ""
  private var session: String = ""


  val timeoutThread = new Thread(new Runnable {
    override def run(): Unit = {
      @tailrec
      def pollUntilTerminated(last: Boolean = false): Unit = {
        val timeoutDelay = pollPeriod * (if (last) 3 else 20)
        val event = try {
          server.flatMap(s => Option(s.events.poll(timeoutDelay, TimeUnit.MILLISECONDS)))
        } catch {
          case _: InterruptedException =>
            return
        }
        event match {
          case None =>
            println(s"Browser closed? Status not polled, timeout ($timeoutDelay sec)")
            // until the app is terminated, give it a chance to reconnect
            if (reportResult.isEmpty) {
              println("  not finished yet, giving a chance to reconnect")
              pollUntilTerminated(true)
            }
          case Some(ServerDoneSent) =>
            println("Final status displayed.")
          case Some(WindowClosedSent) =>
            println("Browser window closed.")
            pollUntilTerminated(true)
          case Some(ServerStatusSent) => //
            pollUntilTerminated()
        }
      }

      pollUntilTerminated()
    }
  })


  abstract class HttpHandlerHelper extends HttpHandler {
    protected def sendResponse(code: Int, t: HttpExchange, responseXml: Elem): Unit = {
      val response = responseXml.toString

      t.sendResponseHeaders(code, response.length)
      val os = t.getResponseBody
      os.write(response.getBytes)
      os.close()
    }

    protected def respondAuthSuccess(t: HttpExchange, state: String): Unit = {
      val scriptText =
      //language=JavaScript
        s"""var finished = false

/**
 * @returns {XMLHttpRequest}
 */
function /** XMLHttpRequest */ ajax() {
  var xmlhttp;
  if (window.XMLHttpRequest) { // code for IE7+, Firefox, Chrome, Opera, Safari
    xmlhttp = new XMLHttpRequest();
  } else { // code  for IE6, IE5
    xmlhttp = new ActiveXObject("Microsoft.XMLHTTP");
  }
  return xmlhttp;
}


function updateStatus() {
  setTimeout(function () {
    var xmlhttp = ajax()
    // the callback function to be callled when AJAX request comes back
    xmlhttp.onreadystatechange = function () {
      if (xmlhttp.readyState == 4) {
        if (xmlhttp.status >= 200 && xmlhttp.status < 300) {
          var response = xmlhttp.responseXML.getElementsByTagName("html")[0];
          document.getElementById("myDiv").innerHTML = response.innerHTML;
          if (xmlhttp.status == 202) {
            updateStatus() // schedule recursively another update
          } else {
            finished = true;
          }
        } else {
          finished = true;
          document.getElementById("myDiv").innerHTML = "<h3>Application not responding</h3>";
        }
      }
    };
    ajaxPost(xmlhttp, "./$statusPath?state=$state", true); // POST to prevent caching
  }, $pollPeriod)
}

function closingCode(){
  if (!finished) {
    var xmlhttp = ajax()
    ajaxPost(xmlhttp, "./$donePath?state=$state", false); // sync to make sure request is send before the window closes
    return null;
  }
}

function ajaxPost(/** XMLHttpRequest */ xmlhttp, /** string */ request, /** boolean */ async) {
  xmlhttp.open("POST", request, async); // POST to prevent caching
  xmlhttp.setRequestHeader("Content-type", "application/x-www-form-urlencoded");
  xmlhttp.send("");

}

"""

      val responseXml = <html>
        <head>
          <script type="text/javascript">
            {scala.xml.Unparsed(scriptText)}
          </script>
        </head>

        <title>Suunto To Strava Authentication</title>
        <body>
          <h1>Suunto To Strava Authenticated</h1>
          <p>Suunto To Strava automated upload application authenticated to Strava</p>

          <div id="myDiv">
            <h3>Starting processing...</h3>
          </div>

        </body>
        <script>
          updateStatus()
          window.onbeforeunload = closingCode;
        </script>
      </html>

      sendResponse(200, t, responseXml)
    }

    protected def respondFailure(t: HttpExchange, error: String): Unit = {
      val responseXml =
        <html>
          <title>Suunto To Strava Authentication</title>
          <body>
            <h1>Suunto To Strava Authenticated</h1>
            <p>This window has expired.<br/>You may have opened another window?<br/>
              Error: {error}
            </p>
            <p>Proceed to:
              <br/>
              <a href="https://www.strava.com">Strava</a> <br/>
            </p>
          </body>
        </html>

      sendResponse(400, t, responseXml)
    }

    protected def respondAuthFailure(t: HttpExchange, error: String): Unit = {
      val responseXml =
        <html>
          <title>Suunto To Strava Authentication</title>
          <body>
            <h1>Suunto To Strava Not Authenticated</h1>
            <p>Suunto To Strava automated upload application not authenticated to Strava.<br/>
              Error: {error}
            </p>
            <p>Proceed to:
              <br/>
              <a href="https://www.strava.com">Strava</a> <br/>
              <a href="https://www.strava.com/settings/apps">Check Strava apps settings</a>
            </p>
          </body>
        </html>

      sendResponse(400, t, responseXml)
    }


  }
  object StatusHandler extends HttpHandlerHelper {
    override def handle(httpExchange: HttpExchange): Unit = {
      val requestURL = httpExchange.getRequestURI.toASCIIString
      println(requestURL)
      val state = requestURL match {
        case statePattern(s) => s
        case _ => ""
      }
      if (session==state) {
        if (reportResult.nonEmpty) {
          val response =
            <html>
              <h3>
                {reportResult}
              </h3>
              <p>Proceed to:
                <br/>
                <a href="https://www.strava.com">Strava</a> <br/>
                <a href="https://www.strava.com/athlete/training">My Activities</a>
              </p>
            </html>

          sendResponse(200, httpExchange, response)
          server.foreach(_.events.put(ServerDoneSent))
        } else {
          val response = <html><h3> {reportProgress} </h3> </html>
          sendResponse(202, httpExchange, response)
          server.foreach(_.events.put(ServerStatusSent))
        }
      } else {
        val response = <error>Invalid session id</error>
        sendResponse(400, httpExchange, response)
        server.foreach(_.events.put(ServerStatusSent))

      }
    }
  }
  object DoneHandler extends HttpHandlerHelper {
    override def handle(t: HttpExchange): Unit = {
      val requestURL = t.getRequestURI.toASCIIString
      println(requestURL)
      val state = requestURL match {
        case statePattern(s) => s
        case _ => ""
      }
      if (session==state) {
        // the session is closed, report to the server
        server.foreach(_.events.put(WindowClosedSent))
      }
      respondFailure(t, "Session closed")
    }
  }

  object AuthHandler extends HttpHandlerHelper {

    def handle(t: HttpExchange): Unit = {
      // Url expected in form: /stravaAuth.html?state=&code=xxxxxxxx
      val requestURL = t.getRequestURI.toASCIIString
      println(requestURL)
      val state = requestURL match {
        case statePattern(s) => s
        case _ => ""
      }
      if (session == "" || session == state) {
        requestURL match {
          case passedPattern(code) =>
            session = state
            respondAuthSuccess(t, state)
            if (!authResult.isCompleted) authResult.success(code)
            else server.foreach(_.events.put(ServerStatusSent))
          case errorPattern(error) =>
            respondAuthFailure(t, error)
            authResult.failure(new IllegalArgumentException(s"Unexpected URL $requestURL"))
          case _ =>
            respondAuthFailure(t, "Unknown error")
            authResult.failure(new IllegalArgumentException(s"Unexpected URL $requestURL"))
        }
      } else {
        respondFailure(t, "Session expired")
      }

    }

  }

  // http://stackoverflow.com/a/3732328/16673
  private def startHttpServer(callbackPort: Int) = {
    val ex = Executors.newSingleThreadExecutor()
    val events = new LinkedBlockingQueue[ServerEvent]()

    val server = HttpServer.create(new InetSocketAddress(8080), 0)
    server.createContext(s"/$callbackPath", AuthHandler)
    server.createContext(s"/$statusPath", StatusHandler)
    server.createContext(s"/$donePath", DoneHandler)

    server.setExecutor(ex) // creates a default executor
    server.start()
    ServerShutdown(server, ex, events)
  }

  def apply(appId: Int, callbackPort: Int, access: String): Option[String] = {
    server = Some(startHttpServer(callbackPort))

    val sessionId = System.currentTimeMillis().toHexString
    val callbackUrl = s"http://localhost:$callbackPort/$callbackPath"
    val forcePrompt = false // useful for debugging / troubleshooting
    val forceStr = if (forcePrompt) "&approval_prompt=force" else ""
    val url = s"https://www.strava.com/oauth/authorize?client_id=$appId&scope=$access&response_type=code&redirect_uri=$callbackUrl&state=$sessionId$forceStr"
    try {
      Desktop.getDesktop.browse(new URL(url).toURI)
    } catch {
      case e: IOException =>
        e.printStackTrace()
    }

    val ret = Try (Await.result(authResult.future, Duration(5, TimeUnit.MINUTES))).toOption

    timeoutThread.start()

    ret
  }

  def progress(status: String): Unit = {
    println(s"Progress: $status")
    reportProgress = status
  }

  def stop(status: String): Unit = {
    reportResult = status
    server.foreach { s =>
      // based on http://stackoverflow.com/a/36129257/16673
      timeoutThread.join()
      // we do not need a CountDownLatch, as Await on the promise makes sure the response serving has already started
      s.executor.shutdown()
      s.executor.awaitTermination(1, TimeUnit.MINUTES); // wait until all tasks complete (i. e. all responses are sent)
      s.server.stop(0)
    }
  }

}

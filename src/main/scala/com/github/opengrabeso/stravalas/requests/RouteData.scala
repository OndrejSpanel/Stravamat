package com.github.opengrabeso.stravalas
package requests

import java.io.OutputStreamWriter

import spark.{Request, Response}

object RouteData extends DefineRequest("/route-data") {

  override def html(req: Request, resp: Response) = {
    val session = req.session

    val id = req.queryParams("id")
    val auth = session.attribute[Main.StravaAuthResult]("auth")

    val contentType = "application/json"
    val events = Storage.load[Main.ActivityEvents]("events-"+id, auth.userId)

    if (events != null) {
      resp.`type`(contentType)
      resp.status(200)

      val out = resp.raw.getOutputStream
      val writer = new OutputStreamWriter(out)
      try {
        writer.write(events.routeJS)
      } finally {
        writer.close()
      }
    } else {
      resp.status(404) // TODO: other errors possible, forward them
    }

    Nil
  }
}
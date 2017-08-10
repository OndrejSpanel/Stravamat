package com.github.opengrabeso.stravamat
package requests

import Main._
import shared.Util._
import spark.{Request, Response}
import com.google.appengine.api.taskqueue._

object Process extends DefineRequest.Post("/process") with ParseFormData {

  def mergeAndUpload(auth: Main.StravaAuthResult, toMerge: Vector[ActivityEvents], sessionId: String): Int = {
    if (toMerge.nonEmpty) {

      val (gpsMoves, attrMovesRaw) = toMerge.partition(_.hasGPS)

      val timeOffset = Settings(auth.userId).questTimeOffset
      val ignoreDuration = 30

      val attrMoves = attrMovesRaw.map(_.timeOffset(-timeOffset))

      def filterIgnored(x: ActivityEvents) = x.isAlmostEmpty(ignoreDuration)

      val timelineGPS = gpsMoves.toList.filterNot(filterIgnored).sortBy(_.startTime)
      val timelineAttr = attrMoves.toList.filterNot(filterIgnored).sortBy(_.startTime)

      val merged = moveslink.MovesLinkUploader.processTimelines(timelineGPS, timelineAttr)

      // store everything into a session storage, and make background tasks to upload it to Strava

      val queue = QueueFactory.getDefaultQueue
      for (upload <- merged) {

        // export here, or in the worker? Both is possible

        // filename is not strong enough guarantee of uniqueness, timestamp should be (in single user namespace)
        val uniqueName = upload.id.id.filename + "_" + System.currentTimeMillis().toString
        // are any metadata needed?
        Storage.store(Main.namespace.upload(sessionId), uniqueName, auth.userId, upload.header, upload)

        // using post with param is not recommended, but it should be OK when not using any payload
        queue add TaskOptions.Builder.withPayload(UploadResultToStrava(uniqueName, auth, sessionId))
        println(s"Queued task $uniqueName")
      }
      merged.size
    } else 0
  }


  override def html(request: Request, resp: Response) = {

    val session = request.session()
    implicit val auth = session.attribute[Main.StravaAuthResult]("auth")
    val sessionId = session.attribute[String]("sid")

    assert(sessionId != null)

    val ops = activities(request)

    val toMerge = ops.flatMap { op =>
      Storage.load[ActivityHeader, ActivityEvents](Main.namespace.stage, op.filename, auth.userId).map(_._2)
    }


    val uploadCount = mergeAndUpload(auth, toMerge, sessionId)

    // used in AJAX only - XML response
    <upload>
      <count>{uploadCount.toString}</count>
    </upload>
  }
}
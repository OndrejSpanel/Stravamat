package net.suunto3rdparty

import moveslink.MovesLinkUploader
import moveslink2.MovesLink2Uploader
import strava.{StravaAPIThisApp, StravaAuth}
import org.apache.log4j.Logger

object Main extends App {
  val log = Logger.getLogger(classOf[App])

  val api = new StravaAPIThisApp

  def getActivitiesIds(checkUploadIds: List[Long], done: Map[Long, Long]): Map[Long, Long] = {
    //noinspection FieldFromDelayedInit
    val pollResults = checkUploadIds.map(id => id -> api.activityIdFromUploadId(id))

    val retryActivities = pollResults.collect {
      case (id, Right(true)) => id
    }

    val doneActivityIds = pollResults.collect {
      case (id, Left(l)) => id -> l
    }

    if (checkUploadIds.isEmpty) done ++ doneActivityIds
    else {
      Thread.sleep(1000) // polling is recommended at most once per second
      getActivitiesIds(retryActivities, done ++ doneActivityIds)
    }
  }


  if (api.authString.nonEmpty) {

    val after = api.mostRecentActivityTime

    log.info("Reading MovesLink2 ...")
    if (!MovesLink2Uploader.checkIfEnvOkay || !MovesLinkUploader.checkIfEnvOkay) {
      StravaAuth.stop("Moveslink not installed correctly", Nil)
      throw new UnsupportedOperationException()
    }
    try {
      val alreadyUploaded = MovesLinkUploader.listAlreadyUploaded()
      val filesToProcess = MovesLinkUploader.listFiles ++ MovesLink2Uploader.listFiles

      MovesLinkUploader.pruneObsolete(alreadyUploaded -- filesToProcess)

      val index = MovesLink2Uploader.readXMLFiles(after, alreadyUploaded, (num, total) => StravaAuth.progress(s"Reading $num of $total GPS files"))
      log.info("Reading MovesLink2 done.")
      log.info("Reading MovesLink ...")
      val uploaded = MovesLinkUploader.uploadXMLFiles(after, api, alreadyUploaded, index, (num, total) => StravaAuth.progress(s"Processing $num of $total files"))
      log.info("Upload MovesLink done.")

      val actIds = getActivitiesIds(uploaded.map(_.id), Map())

      val uploadedActIds = uploaded.map { uid =>
        uid.copy(id = actIds(uid.id))
      }

      StravaAuth.stop(s"Completed, moves uploaded: ${uploaded.size}", uploadedActIds)
    } catch {
      case x: Exception =>
        StravaAuth.stop(s"Completed with exception ${x.getMessage}", Nil)
        throw x
    }
  } else {
    StravaAuth.stop("Canceled", Nil)
  }
}

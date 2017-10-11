package com.github.opengrabeso.stravamat
package requests

import Main._

trait ActivityStorage {

  def storeActivity(stage: String, act: ActivityEvents, userId: String) = {
    Storage.store(stage, act.id.id.filename, userId, act.header, act, Seq("digest" -> act.id.digest), Seq("startTime" -> act.id.startTime.toString))
  }


}

package com.github.opengrabeso.stravalas
package requests
package push
package upload

import spark.{Request, Response}

object PutDigest extends DefineRequest.Post("/push-put-digest") {

  override def html(request: Request, resp: Response) = {
    val path = request.queryParams("path")
    val userId = request.queryParams("user")
    val totalFiles = request.queryParams("total-files").toInt
    val doneFiles = request.queryParams("done-files").toInt


    val digest = request.body()

    // check if such file / digest is already known and report back
    if (Storage.check(Main.namespace.stage, userId, path, digest)) {
      println(s"Received matching digest for $path")
      resp.status(204) // status No content: already present

      Storage.store(Main.namespace.uploadProgress, "progress", userId, Progress(totalFiles, doneFiles))

    } else {
      println(s"Received non-matching digest for $path")

      // debugging opportunity
      //Storage.check(Main.namespace.stage, userId, path, digest)

      resp.status(200) // status OK: not matching - send full file
    }

    Nil
  }


}

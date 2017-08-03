package com.github.opengrabeso.stravalas
package requests
package push
package upload

import java.io.InputStream

import spark.{Request, Response}

object PutFile extends DefineRequest.Post("/push-put") {

  override def html(request: Request, resp: Response) = {
    val path = request.queryParams("path")
    val userId = request.queryParams("user")
    // we expect to receive digest separately, as this allows us to use the stream incrementally while parsing XML
    // - note: client has already computed any it because it verified it before sending data to us
    val digest = request.queryParams("digest")

    val totalFiles = request.queryParams("total-files").toInt
    val doneFiles = request.queryParams("done-files").toInt

    val fileContent = request.raw().getInputStream

    val logProgress = false
    val input = if (logProgress) {
      object WrapStream extends InputStream {
        var progress = 0
        var reported = 0
        val reportEach = 100000

        def report(chars: Int): Unit = {
          progress += chars
          if (progress > reported + reportEach) {
            reported = progress / reportEach * reportEach
            println(s" read $reported B")
          }
        }

        def read() = {
          report(1)
          fileContent.read()
        }

        // TODO: implement, is more efficient
        override def read(b: Array[Byte], off: Int, len: Int) = {
          val read = fileContent.read(b, off, len)
          report(read)
          read
        }
      }
      WrapStream
    } else {
      fileContent
    }

    println(s"Received content for $path")

    Upload.storeFromStreamWithDigest(userId, path, input, digest)

    Storage.store(Main.namespace.uploadProgress, "progress", userId, Progress(totalFiles, doneFiles))

    resp.status(200)

    Nil
  }


}

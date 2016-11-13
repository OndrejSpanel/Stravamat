package com.github.opengrabeso.stravalas
package requests

import spark.{Request, Response}

object Download extends ProcessFile {
  def handle = Handle("/download")

  def process(req: Request, resp: Response, export: Array[Byte], filename: String): Unit = {
    val contentType = "application/octet-stream"

    resp.status(200)
    resp.header("Content-Disposition", filename)
    resp.`type`(contentType)

    val out = resp.raw.getOutputStream
    out.write(export)
  }


}

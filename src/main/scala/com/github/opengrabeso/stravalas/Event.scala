package com.github.opengrabeso.stravalas
import org.joda.time.{DateTime => ZonedDateTime}

case class EventKind(id: String, display: String)

sealed abstract class Event {
  def stamp: ZonedDateTime
  def description: String
  def isSplit: Boolean // splits need to be known when exporting

  def defaultEvent: String

  protected def listSplitTypes: Seq[EventKind] = Seq(
    EventKind("split", "Split"),
    EventKind("splitSwim", "Split (Swim)"),
    EventKind("splitRun", "Split (Run)"),
    EventKind("splitRide", "Split (Ride)")
  )

  def listTypes: Array[EventKind] = (Seq(
    EventKind("", "--"),
    EventKind("lap", "Lap")
  ) ++ listSplitTypes).toArray
}

object Events {

  def typeToDisplay(listTypes: Array[EventKind], name: String): String = {
    listTypes.find(_.id == name).map(_.display).getOrElse("")
  }

  def niceDuration(duration: Int): String = {
    def round(x: Int, div: Int) = (x + div / 2) / div * div
    val minute = 60
    if (duration < minute) {
      s"${round(duration, 5)} sec"
    } else {
      val minutes = duration / minute
      val seconds = duration - minutes * minute
      if (duration < 5 * minute) {
        f"$minutes:${round(seconds, 10)}%2d min"
      } else {
        s"$minutes min"
      }
    }
  }

}

case class PauseEvent(duration: Int, stamp: ZonedDateTime) extends Event {
  def description = s"Pause ${Events.niceDuration(duration)}"
  def defaultEvent = if (duration>=30) "lap" else ""
  def isSplit = false
}
case class PauseEndEvent(duration: Int, stamp: ZonedDateTime) extends Event {
  def description = "Pause end"
  def defaultEvent = if (duration >= 50) "lap" else ""
  def isSplit = false
}
case class LapEvent(stamp: ZonedDateTime) extends Event {
  def description = "Lap"
  def defaultEvent = "lap"
  def isSplit = false
}

case class EndEvent(stamp: ZonedDateTime) extends Event {
  def description = "End"
  def defaultEvent = "end"
  def isSplit = true

  override def listTypes: Array[EventKind] = Array(EventKind("", "--"))
}

case class BegEvent(stamp: ZonedDateTime) extends Event {
  def description = "<b>*** Start activity</b>"
  def defaultEvent = "split"
  def isSplit = true

  override def listTypes = listSplitTypes.toArray
}

case class SplitEvent(stamp: ZonedDateTime) extends Event {
  def description = "Split"
  def defaultEvent = "split"
  def isSplit = true
}

trait SegmentTitle {
  def isPrivate: Boolean
  def name: String
  def title = {
    val segTitle = if (isPrivate) "private segment" else "segment"
    s"$segTitle $name"
  }

}

case class StartSegEvent(name: String, isPrivate: Boolean, stamp: ZonedDateTime) extends Event with SegmentTitle {
  def description: String = s"Start $title"
  def defaultEvent = ""
  def isSplit = false
}
case class EndSegEvent(name: String, isPrivate: Boolean, stamp: ZonedDateTime) extends Event with SegmentTitle {
  def description: String = s"End $title"
  def defaultEvent = ""
  def isSplit = false
}


case class EditableEvent(var action: String, time: Int, km: Double, sport: String) {
  override def toString: String = {
    s""""$action", $time, $km, "$sport""""
  }
}

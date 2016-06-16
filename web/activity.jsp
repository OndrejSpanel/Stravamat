<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ page import="com.github.opengrabeso.stravalas.*" %>

<html>
<head>
  <title>Strava Split And Lap</title>
</head>

<body>

<p>Activity ID <%= request.getParameter("activityId")%>
</p>

<%
  String authToken = (String)session.getAttribute("authToken");
  String actId = request.getParameter("activityId");
  String[] laps = Main.getLapsFrom(authToken, actId);

%>

<p>Original laps:</p>
<% for (String lap : laps) { %>
<p>Lap time <%= lap %>.</p>
<% } %>
<p>Split at:</p>

<p>Counting to three:</p>
<p>OK.</p>

</body>
</html>

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import model.LiftRide;
import model.ResponseMsg;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

import java.util.logging.Logger;

public class SkierServlet extends HttpServlet {
    private Gson gson = new Gson();

    private static final Logger logger =
            Logger.getLogger(SkierServlet.class.getName());

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        logger.info("GET request received: " + req.getPathInfo());

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String urlPath = req.getPathInfo();

        // Check we have a URL
        if (urlPath == null || urlPath.isEmpty() || "/".equals(urlPath)) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            res.getWriter().write(gson.toJson(new ResponseMsg("missing parameters")));
            return;
        }

        String[] urlParts = urlPath.split("/");

        if (!isUrlValid(urlParts)) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write(gson.toJson(new ResponseMsg("invalid url")));
        } else {
            res.setStatus(HttpServletResponse.SC_OK);

            // Extract parameters
            String resortID = urlParts[1];
            String seasonID = urlParts[3];
            String dayID = urlParts[5];
            String skierID = urlParts[7];

            // Create response
            ResponseMsg response = new ResponseMsg(
                    String.format("GET request processed for skier %s at resort %s, season %s, day %s",
                            skierID, resortID, seasonID, dayID)
            );

            res.getWriter().write(gson.toJson(response));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        logger.info("POST request received: " + req.getPathInfo());

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String urlPath = req.getPathInfo();

        // Check we have a URL
        if (urlPath == null || urlPath.isEmpty() || "/".equals(urlPath)) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write(gson.toJson(new ResponseMsg("missing parameters")));
            return;
        }

        String[] urlParts = urlPath.split("/");

        if (!isUrlValid(urlParts)) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write(gson.toJson(new ResponseMsg("invalid url")));
            return;
        }

        // Parse JSON body
        try {
            StringBuilder jsonBody = new StringBuilder();
            BufferedReader reader = req.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBody.append(line);
            }

            LiftRide liftRide = gson.fromJson(jsonBody.toString(), LiftRide.class);

            if (liftRide == null || !isLiftRideValid(liftRide)) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON body")));
                return;
            }

            // Extract URL parameters
            String resortID = urlParts[1];
            String seasonID = urlParts[3];
            String dayID = urlParts[5];
            String skierID = urlParts[7];

            // Process the lift ride (in real app, save to database)
            res.setStatus(HttpServletResponse.SC_CREATED);
            ResponseMsg response = new ResponseMsg(
                    String.format("Lift ride recorded: skier %s, resort %s, season %s, day %s, time %d, lift %d",
                            skierID, resortID, seasonID, dayID, liftRide.getTime(), liftRide.getLiftID())
            );

            res.getWriter().write(gson.toJson(response));

        } catch (JsonSyntaxException e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON body")));
        }
    }

    private boolean isUrlValid(String[] urlParts) {
        if (urlParts.length != 8) {
            return false;
        }

        if (!urlParts[2].equals("seasons") ||
                !urlParts[4].equals("days") ||
                !urlParts[6].equals("skiers")) {
            return false;
        }

        try {
            int resortID = Integer.parseInt(urlParts[1]);
            int seasonID = Integer.parseInt(urlParts[3]);
            int dayID = Integer.parseInt(urlParts[5]);
            int skierID = Integer.parseInt(urlParts[7]);

            return resortID > 0 &&
                    seasonID > 0 &&
                    dayID > 0 &&
                    skierID > 0;

        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isLiftRideValid(LiftRide liftRide) {
        return liftRide.getTime() >= 1 &&
                liftRide.getTime() <= 360 &&
                liftRide.getLiftID() >= 1 &&
                liftRide.getLiftID() <= 40;
    }
}
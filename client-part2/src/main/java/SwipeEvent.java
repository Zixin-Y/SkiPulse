public final class SwipeEvent {
    // Generate one ID per logical swipe and preserve it across retries.
    private final String eventId = java.util.UUID.randomUUID().toString();

    private final int resortID;
    private final int seasonID;
    private final int dayID;
    private final int skierID;
    private final int time;
    private final int liftID;

    public SwipeEvent(int resortID, int seasonID, int dayID,
                      int skierID, int time, int liftID) {
        this.resortID = resortID;
        this.seasonID = seasonID;
        this.dayID = dayID;
        this.skierID = skierID;
        this.time = time;
        this.liftID = liftID;
    }

    public String getEventId() {
        return eventId;
    }

    public int getResortID() {
        return resortID;
    }

    public int getSeasonID() {
        return seasonID;
    }

    public int getDayID() {
        return dayID;
    }

    public int getSkierID() {
        return skierID;
    }

    public int getTime() {
        return time;
    }

    public int getLiftID() {
        return liftID;
    }

    public String toPath() {
        return "/skiers/" + resortID
                + "/seasons/" + seasonID
                + "/days/" + dayID
                + "/skiers/" + skierID;
    }

    public String toJson() {
        return "{\"time\":" + time
                + ",\"liftID\":" + liftID + "}";
    }
}
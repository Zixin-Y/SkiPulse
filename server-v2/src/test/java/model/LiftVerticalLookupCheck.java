package model;

public final class LiftVerticalLookupCheck {

    public static void main(String[] args) {

        // Use the supplied table as the expected result for every lift.
        int[] expected = {
                167, 74, 344, 273, 383, 397, 104, 88, 288, 183,
                204, 214, 222, 104, 364, 357, 213, 336, 173, 223,
                267, 315, 250, 200, 368, 181, 223, 88, 138, 99,
                262, 213, 70, 125, 340, 63, 296, 103, 374, 269
        };

        // Verify all 40 mappings, including the first and last lift.
        for (int liftID = 1; liftID <= expected.length; liftID++) {
            int actual = LiftVerticalLookup.getVertical(liftID);
            int expectedVertical = expected[liftID - 1];

            if (actual != expectedVertical) {
                throw new AssertionError(
                        "Lift " + liftID
                                + ": expected " + expectedVertical
                                + ", but got " + actual
                );
            }
        }

        System.out.println("PASS: all 40 lift mappings are correct.");

        // Verify the assignment's rule that every vertical is 60-400 ft.
        for (int liftID = 1; liftID <= expected.length; liftID++) {
            int vertical = LiftVerticalLookup.getVertical(liftID);

            if (vertical < 60 || vertical > 400) {
                throw new AssertionError(
                        "Lift " + liftID + " vertical " + vertical
                                + " is outside the 60-400 ft range"
                );
            }
        }

        System.out.println("PASS: every vertical is within 60-400 ft.");

        // Verify that IDs outside the supported range are rejected.
        expectInvalidLift(-1);
        expectInvalidLift(0);
        expectInvalidLift(41);
        expectInvalidLift(Integer.MAX_VALUE);

        System.out.println("PASS: invalid lift IDs are rejected.");

        // Verify accumulation using two rides by the same skier.
        int total = LiftVerticalLookup.getVertical(1)
                + LiftVerticalLookup.getVertical(2);

        if (total != 241) {
            throw new AssertionError(
                    "Expected a total of 241 feet, but got " + total
            );
        }

        System.out.println("PASS: lifts 1 and 2 total 241 feet.");
        System.out.println("All lift vertical lookup checks passed.");
    }

    // Fail the check if an invalid ID is accepted.
    private static void expectInvalidLift(int liftID) {
        try {
            LiftVerticalLookup.getVertical(liftID);
        } catch (IllegalArgumentException expected) {
            return;
        }

        throw new AssertionError(
                "Expected rejection of invalid lift ID: " + liftID
        );
    }
}
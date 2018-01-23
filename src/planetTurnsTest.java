import hlt.Constants;

public class planetTurnsTest {

	static int numTurnsUntilFullyDocked(int cp, int spots, int ships) {
		if (ships <= 0) return -1;
		int nTurns = 0;
		if (ships >= spots) {
			return nTurns;
		}
		if (cp == 0) { // correction for if the ships aren't fully docked
			if (ships == 1) nTurns += 2;
			else if (ships == 2) nTurns += 1;
		}
		while (ships < spots) {
			double rp = Math.max(Constants.SHIP_COST - cp, 0);
			int newTurns = 0;
			if (ships == 1) {
				newTurns = (int) Math.ceil(rp / ((double) Constants.BASE_PRODUCTIVITY));				
			} else if (ships == 2) {
				newTurns = Math.max(1, (int) Math.ceil(rp / (1.5 * Constants.BASE_PRODUCTIVITY)));
			} else if (ships >= 3) {
				newTurns = Math.max(1, (int) Math.ceil(rp / ((ships - 1) * Constants.BASE_PRODUCTIVITY)));
			}
			nTurns += newTurns;
			cp = Math.max(0, cp + nTurns*Constants.BASE_PRODUCTIVITY - Constants.SHIP_COST);
			ships++;
		}
		return nTurns;
	}
	
	public static void main(String[] args) {
		for (int ships = 0; ships <= 6; ships++) {
			System.out.print(ships + " ships: ");
			for (int spots = 1; spots <= 6; spots++) {
				System.out.print(spots + " " + numTurnsUntilFullyDocked(30, spots, ships) + ". ");
			}
			System.out.println();
		}

	}

}

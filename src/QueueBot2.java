import java.util.ArrayList;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.Map.Entry;

import hlt.Constants;
import hlt.DockMove;
import hlt.GameMap;
import hlt.Log;
import hlt.Move;
import hlt.Navigation;
import hlt.Networking;
import hlt.Planet;
import hlt.Position;
import hlt.Ship;
import hlt.ThrustMove;

public class QueueBot2 {
	
	static class TargetPriority implements Comparable<TargetPriority> {
		/**
		 * @param target
		 * @param priority
		 */
		public TargetPriority(Position target, Double priority) {
			super();
			this.target = target;
			this.priority = priority;
		}

		Position target;
		Double priority;

		@Override
		public int compareTo(TargetPriority o) {
			return Double.compare(this.priority, o.priority);
		}
	}

    public static void main(final String[] args) {
        final Networking networking = new Networking();
        final GameMap gameMap = networking.initialize("QueueBot2");

        // We now have 1 full minute to analyze the initial map.
        final String initialMapIntelligence =
                "width: " + gameMap.getWidth() +
                "; height: " + gameMap.getHeight() +
                "; players: " + gameMap.getAllPlayers().size() +
                "; planets: " + gameMap.getAllPlanets().size();
        Log.log(initialMapIntelligence);

        final ArrayList<Move> moveList = new ArrayList<>();
        for (;;) {
            moveList.clear();
            networking.updateMap(gameMap);
            
            PriorityQueue<TargetPriority> queue = new PriorityQueue<>();
            PriorityQueue<TargetPriority> microQueue = new PriorityQueue<>();

            for (final Ship ship : gameMap.getMyPlayer().getShips().values()) {
                if (ship.getDockingStatus() != Ship.DockingStatus.Undocked) {
                    continue;
                }
                
                queue.clear();
                boolean microNearPlanet = false;
                planetLoop:
                for (final Planet planet : gameMap.getAllPlanets().values()) {
                    if (ship.withinDockingRange(planet)) {
                    	
                    	// check if there are hostiles near the planet that we need to fight
                    	HashMap<Double, Ship> hostilesNearPlanet = new HashMap<>(gameMap.hostilesNearPlanet(planet));
                    	
                    	// fight hostiles if necessary
                    	if (hostilesNearPlanet.size() > 0) {
                    		microQueue.clear();
                    		for (Ship hostile : hostilesNearPlanet.values()) {
                    			microQueue.add(new TargetPriority(hostile, ship.getDistanceTo(hostile)));
                    		}
                    		TargetPriority t;
                        	while ((t = microQueue.poll()) != null) {
        	                	//final ThrustMove newThrustMove = Navigation.navigateShipToDock(gameMap, ship, (Planet) t.target, Constants.MAX_SPEED*6/7);
        	                	final ThrustMove newThrustMove =
        	                			Navigation.navigateShipTowardsTarget(gameMap, ship,
        	                					t.target, Constants.MAX_SPEED*6/7, true,
        	                					Constants.MAX_NAVIGATION_CORRECTIONS, Math.PI/180.0);
        	                	if (newThrustMove != null) {
        	                		moveList.add(newThrustMove);
        	                		microNearPlanet = true;
        	                		break planetLoop;
        	                	}
                        	}
                    	} else if (!planet.isFull()) { // dock if planet is not full
	                    	microNearPlanet = true;
	                        moveList.add(new DockMove(ship, planet));
	                        break;
                    	}
                    }

                    // pick a good planet to go to
                    double dist = planet.getDistanceTo(ship);
                    double cost = 0;
                    if (planet.getOwner() == gameMap.getMyPlayerId()) {
                    	cost = dist * 1000;
                    } else if (!planet.isOwned()) {
                    	cost = dist;
                    } else {
                    	cost = dist * 4;
                    }
                    queue.add(new TargetPriority(planet, cost));
                }
                
                if (!microNearPlanet) {
                	// navigate to planet with highest order in queue that is possible
                	TargetPriority t;
//                	Log.log(ship.getId() + " " + queue.size());
                	while ((t = queue.poll()) != null) {
	                	final ThrustMove newThrustMove = Navigation.navigateShipToDock(gameMap, ship,
	                			(Planet) t.target, Constants.MAX_SPEED*6/7);
	                	if (newThrustMove != null) {
	                		moveList.add(newThrustMove);
	                		break;
	                	}
                	}
                }
            }
            Networking.sendMoves(moveList);
        }
    }
}

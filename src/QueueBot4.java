import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

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

public class QueueBot4 {
	
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
        final GameMap gameMap = networking.initialize("QueueBot4");

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
            
            HashMap<Integer, Map<Double, Ship>> nearbyHostiles = new HashMap<>(); 
            for (final Planet planet : gameMap.getAllPlanets().values()) {
            	nearbyHostiles.put(planet.getId(), gameMap.hostilesNearPlanet(planet));
            }

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
                    	HashMap<Double, Ship> hostilesNearPlanet = new HashMap<Double, Ship>(nearbyHostiles.get(planet.getId()));
                    	
                    	// fight hostiles if necessary
                    	if (hostilesNearPlanet.size() > 0) {
                    		microQueue.clear();
                    		for (Ship hostile : hostilesNearPlanet.values()) {
                    			microQueue.add(new TargetPriority(hostile, ship.getDistanceTo(hostile)));
                    		}
                    		TargetPriority t;
                        	while ((t = microQueue.poll()) != null) {
        	                	//final ThrustMove newThrustMove = Navigation.navigateShipToDock(gameMap, ship, (Planet) t.target, Constants.MAX_SPEED*6/7);
        	                	if (t.priority < Constants.WEAPON_RADIUS) {
        	                		microNearPlanet = true;
        	                		break planetLoop;
        	                	}
                        		
                        		final ThrustMove newThrustMove =
        	                			Navigation.navigateShipToHostileShip(gameMap, ship,
        	                					(Ship) t.target, Constants.MAX_SPEED);
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
                    	Map<Double, Ship> nh = nearbyHostiles.get(planet.getId()); 
                    	if (!nh.isEmpty()) {
                    		cost = dist * 100 / Math.sqrt(nh.size());
                    	}
                    } else if (!planet.isOwned()) {
                    	cost = dist;
                    } else {
                    	cost = dist * 2;
                    }
                    cost *= 3 + planet.getDockingSpots();
                    queue.add(new TargetPriority(planet, cost));
                }
                
                if (!microNearPlanet) {
                	// navigate to planet with highest order in queue that is possible
                	TargetPriority t;
//                	Log.log(ship.getId() + " " + queue.size());
                	while ((t = queue.poll()) != null) {
	                	final ThrustMove newThrustMove = Navigation.navigateShipToDock(gameMap, ship,
	                			(Planet) t.target, Constants.MAX_SPEED);
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

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

public class QueueBot5 {
	
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
        final GameMap gameMap = networking.initialize("QueueBot5");

        // We now have 1 full minute to analyze the initial map.
        final String initialMapIntelligence =
                "width: " + gameMap.getWidth() +
                "; height: " + gameMap.getHeight() +
                "; players: " + gameMap.getAllPlayers().size() +
                "; planets: " + gameMap.getAllPlanets().size();
        Log.log(initialMapIntelligence);
        
        double gameProgressParam = 0;

        final ArrayList<Move> moveList = new ArrayList<>();
        for (;;) {
            moveList.clear();
            networking.updateMap(gameMap);
            
            gameProgressParam = 0.8 + 3.0/(1.0 + Math.exp(-0.05*(gameMap.getAllShips().size() - 100)));
            
            PriorityQueue<TargetPriority> queue = new PriorityQueue<>();
            PriorityQueue<TargetPriority> microQueue = new PriorityQueue<>();
            
            HashMap<Integer, Map<Double, Ship>> plHostiles = new HashMap<>(); 
            HashMap<Integer, Map<Double, Ship>> plUdHostiles = new HashMap<>();
            HashMap<Integer, Integer> numplHostiles = new HashMap<>();
            HashMap<Integer, Integer> numplUdHostiles = new HashMap<>();
            HashMap<Integer, Integer> numplDHostiles = new HashMap<>();
            for (final Planet planet : gameMap.getAllPlanets().values()) {
            	Map<Double, Ship> aMap = gameMap.hostilesNearPlanet(planet);
            	Map<Double, Ship> bMap = gameMap.undockedHostilesNearPlanet(planet);
            	
            	plHostiles.put(planet.getId(), aMap);
            	numplHostiles.put(planet.getId(), aMap.size());
            	plUdHostiles.put(planet.getId(), bMap);
            	numplUdHostiles.put(planet.getId(), bMap.size());
            	numplDHostiles.put(planet.getId(), aMap.size() - bMap.size());
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
                    	HashMap<Double, Ship> hostilesNearPlanet = new HashMap<Double, Ship>(plHostiles.get(planet.getId()));
                    	
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
                    int numUdHostiles = numplUdHostiles.get(planet.getId());
                    int numDHostiles = numplDHostiles.get(planet.getId());
                    if (planet.getOwner() == gameMap.getMyPlayerId()) {
                    	cost = dist * 1000;
                    	Map<Double, Ship> nh = plHostiles.get(planet.getId()); 
                    	if (!nh.isEmpty()) {  // to defend
                    		cost = dist * 100 / Math.sqrt(nh.size());
                    	}
                    } else {
                    	cost = dist * (((double) numUdHostiles)*0.3 + ((double) numDHostiles)*0.2 + 0.4);
                    }
                    cost *= gameProgressParam + planet.getDockingSpots();
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

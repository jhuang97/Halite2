import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;

import hlt.Collision;
import hlt.Constants;
import hlt.DisjointSet;
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

public class QueueBot12Bad {
	
	static class TargetPriority implements Comparable<TargetPriority> {
		
		public TargetPriority(Position target, Double priority, ObType ob) {
			super();
			this.target = target;
			this.priority = priority;
			this.ob = ob;
			this.plDefend = null;
		}

		public TargetPriority(Position target, Double priority, ObType ob, Planet plDefend) {
			super();
			this.target = target;
			this.priority = priority;
			this.ob = ob;
			this.plDefend = plDefend;
		}

		Position target;
		Double priority;
		ObType ob;
		Planet plDefend;

		@Override
		public int compareTo(TargetPriority o) {
			return Double.compare(this.priority, o.priority);
		}
	}

	static int numTurnsUntilFullyDocked(Planet p) {
		int cp = p.getCurrentProduction();
		int spots = p.getDockingSpots();
		int ships = p.getDockedShips().size();
		
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
	
	static enum ObType {
		TRAVEL_GET_PLANET,
		TRAVEL_GET_HOSTILE_PLANET,
		TRAVEL_FILL_PLANET,
		TRAVEL_DEFEND_PLANET,
		TRAVEL_FRIENDLY_PLANET,
		TRAVEL_COMBAT_THREATENING_SHIP,
		COMBAT_NEAR_PLANET,
		COMBAT_NEAR_PLANET_STILL,
		DOCK
	}
	
	static class Objective {
		ObType type;
		Position currentPos;
		Position nextPos;
		Position targetPlanet;
		Ship targetShip;
	}
	
    public static void main(final String[] args) {
        final Networking networking = new Networking();
        final GameMap gameMap = networking.initialize("QueueBot12");
        // RIP!  This one is so bad that it might be beyond salvaging.

        int numPlayers = gameMap.getAllPlayers().size();
        
        // We now have 1 full minute to analyze the initial map.
        final String initialMapIntelligence =
                "width: " + gameMap.getWidth() +
                "; height: " + gameMap.getHeight() +
                "; players: " + numPlayers +
                "; planets: " + gameMap.getAllPlanets().size();
        Log.log(initialMapIntelligence);
        
        double gameProgressParam = 0;

        final ArrayList<Move> moveList = new ArrayList<>();
        final ArrayList<Ship> shipList = new ArrayList<>();
        
        HashMap<Integer, ObType> prevOb = new HashMap<>(); // ship id to obtype
        HashMap<Integer, Integer> prevDefending = new HashMap<>(); // ship id to planet id
        
        double threatParam = (numPlayers == 2) ? 1.1 : 10;
        double threatParam2 = Constants.SHIP_RADIUS + Constants.DOCK_RADIUS + Constants.WEAPON_RADIUS +
        		((numPlayers == 2) ? 1.0 : 0.2) * Constants.DOCK_TURNS * Constants.MAX_SPEED; 
        double friendlyDist = Constants.DOCK_TURNS * Constants.MAX_SPEED * 0.8;
        
//        boolean grewPast3Ships = false;
        for (;;) {
//        	if (gameMap.getMyPlayer().getShips().size() > 3) grewPast3Ships = true;
            moveList.clear();
            shipList.clear();
            networking.updateMap(gameMap);
            
            gameProgressParam = 0.8 + 3.0/(1.0 + Math.exp(-0.05*(gameMap.getAllShips().size() - 100)));
            
            PriorityQueue<TargetPriority> queue = new PriorityQueue<>();
            PriorityQueue<TargetPriority> microQueue = new PriorityQueue<>();
            
            // list the hostile ships near each planet
            HashMap<Integer, Map<Double, Ship>> plHostiles = new HashMap<>();
            HashMap<Integer, Map<Double, Ship>> plUdHostiles = new HashMap<>();
            HashMap<Integer, Integer> numplHostiles = new HashMap<>();
            HashMap<Integer, Integer> numplUdHostiles = new HashMap<>();
            HashMap<Integer, Integer> numplDHostiles = new HashMap<>();
            String planetOwners = "";
            for (final Planet planet : gameMap.getAllPlanets().values()) {
            	if (planet.getOwner() != -1)
            		planetOwners += planet.getId() + ", " + planet.getOwner() + ". ";
            }
            if (planetOwners.length() > 0) Log.log(planetOwners);
            for (final Planet planet : gameMap.getAllPlanets().values()) {
            	Map<Double, Ship> aMap = gameMap.hostilesNearPlanet(planet);
            	Map<Double, Ship> bMap = gameMap.undockedHostilesNearPlanet(planet);
            	
            	plHostiles.put(planet.getId(), aMap);
            	numplHostiles.put(planet.getId(), aMap.size());
            	plUdHostiles.put(planet.getId(), bMap);
            	numplUdHostiles.put(planet.getId(), bMap.size());
            	numplDHostiles.put(planet.getId(), aMap.size() - bMap.size());
//            	if (planet.getOwner() == gameMap.getMyPlayerId()) {
//            		Log.log(planet.toString());
//            	}
            }

            // look through all the planets.  dock if it's a good idea.  otherwise
            // assign score to each planet
            for (final Ship ship : gameMap.getMyPlayer().getShips().values()) {
                if (ship.getDockingStatus() != Ship.DockingStatus.Undocked) {
                    continue;
                }
                
                queue.clear();
                boolean microNearPlanet = false;
                
                planetLoop:
                for (final Planet planet : gameMap.getAllPlanets().values()) {
                	boolean notReadyToFullyDockNearestPlanet = false;
                	int numAvailableFriendlyShips = Math.max(0, gameMap.undockedFriendliesNearPlanet(planet, friendlyDist).size()
            				- (planet.getDockingSpots() - planet.getDockedShips().size()));
                	Map<Double, Ship> shipsThatCanThreaten = gameMap.hostilesNearPlanet(planet, threatParam2);
                	
                    if (ship.withinDockingRange(planet)) {
                    	
                    	// check if there are hostiles near the planet that we need to fight
                    	HashMap<Double, Ship> hostilesNearPlanet = new HashMap<Double, Ship>(plHostiles.get(planet.getId()));
                    	
                    	// fight hostiles if necessary
                    	if (hostilesNearPlanet.size() > 0) {
                    		microQueue.clear();
                    		for (Ship hostile : hostilesNearPlanet.values()) {
                    			microQueue.add(new TargetPriority(hostile, ship.getDistanceTo(hostile), ObType.COMBAT_NEAR_PLANET));
                    		}
                    		TargetPriority t;
                        	while ((t = microQueue.poll()) != null) {
        	                	//final ThrustMove newThrustMove = Navigation.navigateShipToDock(gameMap, ship, (Planet) t.target, Constants.MAX_SPEED*6/7);
        	                	if (t.priority < Constants.WEAPON_RADIUS) {
        	                		microNearPlanet = true;
//        	                		Log.log("ship " + ship.getId() + " defends planet " + planet.getId() + "; fights ship " + ((Ship) t.target).getId() + "; no move");
        	                		prevOb.put(ship.getId(), ObType.COMBAT_NEAR_PLANET_STILL);
        	                		break planetLoop;
        	                	}
                        		
                        		final ThrustMove newThrustMove =
        	                			Navigation.navigateShipToHostileShipNearPlanet(gameMap, ship,
        	                					(Ship) t.target, planet, Constants.MAX_SPEED);
        	                	if (newThrustMove != null) {
        	                		moveList.add(newThrustMove);
        	                		shipList.add(ship);
        	                		microNearPlanet = true;
//        	                		Log.log("ship " + ship.getId() + " defends planet " + planet.getId() + "; fights ship " + ((Ship) t.target).getId());
        	                		prevOb.put(ship.getId(), ObType.COMBAT_NEAR_PLANET);
        	                		break planetLoop;
        	                	}
                        	}
                    	} else if (!planet.isFull()) {
                    		// if planet is dockable but there are threatening docked enemy ships nearby,
                    		// fight them
                    		if (shipsThatCanThreaten.size() > threatParam * numAvailableFriendlyShips) {
                    			notReadyToFullyDockNearestPlanet = true;
                    			Log.log(String.format("Ship %d is too scared to dock at planet %d", ship.getId(), planet.getId()));
                    			Iterator<Entry<Double, Ship>> it = shipsThatCanThreaten.entrySet().iterator();
                    			boolean addedStuffToQueue = false;
                    			while (it.hasNext()) {
                    				Entry<Double, Ship> e = it.next();
                    				double cost = e.getKey()/2;
                    				if (e.getValue().getDockingStatus() != Ship.DockingStatus.Undocked) {
                    					cost /= 10;
                    					queue.add(new TargetPriority(e.getValue(), cost, ObType.TRAVEL_COMBAT_THREATENING_SHIP, planet));
                    					addedStuffToQueue = true;
                    				}
                    			}
                    			if (addedStuffToQueue) continue;
                    		} else { // dock if planet is not full
		                    	microNearPlanet = true;
		                        moveList.add(new DockMove(ship, planet));
		                        shipList.add(ship);
	//	                        Log.log("ship " + ship.getId() + " docks at planet " + planet.getId());
		                        prevOb.put(ship.getId(), ObType.DOCK);
		                        break;
                    		}
                    	}
                    }
                    if (prevOb.get(ship.getId()) == ObType.TRAVEL_COMBAT_THREATENING_SHIP
                    	&& prevDefending.get(ship.getId()) == planet.getId()) {
                    	// straight up copy-pasted from above.  alas.
                		if (shipsThatCanThreaten.size() > threatParam * numAvailableFriendlyShips) {
                			notReadyToFullyDockNearestPlanet = true;
                			Log.log(String.format("Ship %d is still too scared to dock at planet %d", ship.getId(), planet.getId()));
                			Iterator<Entry<Double, Ship>> it = shipsThatCanThreaten.entrySet().iterator();
                			boolean addedStuffToQueue = false;
                			while (it.hasNext()) {
                				Entry<Double, Ship> e = it.next();
                				double cost = e.getKey()/2;
                				if (e.getValue().getDockingStatus() != Ship.DockingStatus.Undocked) {
                					cost /= 10;
                					queue.add(new TargetPriority(e.getValue(), cost, ObType.TRAVEL_COMBAT_THREATENING_SHIP, planet));
                					addedStuffToQueue = true;
                					Log.log(String.format("ship %d queued for TCTS: %.3f towards ship %d", 
                							ship.getId(), cost, e.getValue().getId()));
                				}
                			}
                			if (addedStuffToQueue) continue;
                		}
                    }

                    // pick a good planet to go to: assign a score to the current planet
                    double dist = planet.getDistanceTo(ship) - planet.getRadius();
                    double cost = 0;
                    int numUdHostiles = numplUdHostiles.get(planet.getId());
                    int numDHostiles = numplDHostiles.get(planet.getId());
                    ObType ob;
                    if (planet.getOwner() == gameMap.getMyPlayerId()) {
                    	if (numTurnsUntilFullyDocked(planet) < dist/Constants.MAX_SPEED) {
                    		// friendly planet will be filled up before this ship gets there
	                    	Map<Double, Ship> nh = plHostiles.get(planet.getId()); 
	                    	if (!nh.isEmpty()) {  // to defend
	                    		cost = dist * 100 / (10 + Math.sqrt((double) nh.size())) * (dist/7.0 / 2);
	                    		ob = ObType.TRAVEL_DEFEND_PLANET;
	                    	} else {
	                    		cost = dist * 250;
	                    		ob = ObType.TRAVEL_FRIENDLY_PLANET;
	                    	}
                    	} else {
                    		if (!planet.isFull() && ship.withinDockingRange(planet)) {
	//	                        Log.log("ship " + ship.getId() + " docks at planet " + planet.getId());
		                        cost = 5*Math.exp((shipsThatCanThreaten.size() - threatParam * numAvailableFriendlyShips)/2.0);
                    			ob = ObType.DOCK;
                    		} else {
                    			cost = dist * (((double) numUdHostiles)*0.35 + ((double) numDHostiles)*0.15 + 0.4);
                    			ob = ObType.TRAVEL_FILL_PLANET;
                    		}
                    	}
                    	queue.add(new TargetPriority(planet, cost, ob));
                    } else if (!notReadyToFullyDockNearestPlanet) {
                    	cost = dist * (((double) numUdHostiles)*0.35 + ((double) numDHostiles)*0.15 + 0.4);
                    	if (!planet.isOwned()) {
                    		ob = ObType.TRAVEL_GET_PLANET;
                    		queue.add(new TargetPriority(planet, cost, ob));
                    	}
                    	else {
                    		ob = ObType.TRAVEL_GET_HOSTILE_PLANET;
                    		Log.log("queueing TGHP: ship " + ship.getId() + ", toward planet " + planet.getId() + ", " + planet.getDockedShips());
                        	for (Integer id : planet.getDockedShips()) {
                        		Ship dockedEnemyShip = gameMap.getShip(planet.getOwner(), id);
                        		double sDist = dockedEnemyShip.getDistanceTo(ship);
                        		double sCost = sDist * (((double) numUdHostiles)*0.35 + ((double) numDHostiles)*0.15 + 0.4);
                        		queue.add(new TargetPriority(dockedEnemyShip, sCost, ob));
//                        		Log.log(String.format("ship %d queued for TGHP: %.3f towards ship %d", 
//            							ship.getId(), cost, dockedEnemyShip.getId()));
                        	}
                    	}
                    }
                }
                
                if (!microNearPlanet) {
                	// navigate to planet with highest order in queue that is possible
                	TargetPriority t;
//                	Log.log(ship.getId() + " " + queue.size());
                	while ((t = queue.poll()) != null) {
                		if (t.ob == ObType.DOCK) {
	                        moveList.add(new DockMove(ship, (Planet) t.target));
	                        shipList.add(ship);
	                        Log.log("ship " + ship.getId() + " docks at planet " + ((Planet) t.target).getId()
	                        		+ ", cost = " + String.format("%.2f", t.priority));
	                        prevOb.put(ship.getId(), ObType.DOCK);
	                        break;
                		} else {
	                		final ThrustMove newThrustMove;
	                		if (t.ob == ObType.TRAVEL_COMBAT_THREATENING_SHIP
	                				|| t.ob == ObType.TRAVEL_GET_HOSTILE_PLANET) {
	                			newThrustMove = Navigation.navShipToHostileShip_v2(
	                					gameMap, ship, (Ship) t.target, Constants.MAX_SPEED);
	                			
	                		} else {
	                			newThrustMove = Navigation.navShipToDock_v2(gameMap, ship,
		                			(Planet) t.target, Constants.MAX_SPEED);
	                		}
		                	if (newThrustMove != null) {
		                		moveList.add(newThrustMove);
		                		
		                		// update prevOb and prevDefending
		                		prevOb.put(ship.getId(), t.ob);
		                		if (t.ob == ObType.TRAVEL_COMBAT_THREATENING_SHIP) {
		                			prevDefending.put(ship.getId(), t.plDefend.getId());
		                		} else {
		                			prevDefending.put(ship.getId(), null);
		                		}
		                		shipList.add(ship);
		                		
		                		int id = -1;
		                		Position p = t.target;
		                		double dist = p.getDistanceTo(ship);
		                		if (p instanceof Planet) {
		                			dist -= ((Planet) p).getRadius();
		                			id = ((Planet) p).getId();
		                		} else if (p instanceof Ship) {
		                			id = ((Ship) p).getId();
		                		}
		                		Log.log(String.format("ship %d %s %d, cost: %.2f / dist: %.2f = %.3f",
		                				ship.getId(), t.ob.toString(), id, t.priority, dist, t.priority/dist));
		                		break;
		                	}
	                	}
                	}
                }
            }
            
            // detect collisions
            ArrayList<ArrayList<Integer>> collisionGroups = new ArrayList<>();
            ArrayList<Integer> thrustMoveIdx = new ArrayList<>();
        	// Things are messy here.  We have thrustMove indices and moveList indices.
	        for (int i = 0; i < moveList.size(); i++) {
	        	if (moveList.get(i) instanceof ThrustMove) {
	        		thrustMoveIdx.add(i);
	        	}
	        }
//            if (grewPast3Ships) {
	            DisjointSet collisionSets = DisjointSet.makeSingletons(thrustMoveIdx.size());
	            for (int i = 0; i < thrustMoveIdx.size(); i++) {
	            	for (int j = i+1; j < thrustMoveIdx.size(); j++) {
	            		int m = thrustMoveIdx.get(i);
	            		int n = thrustMoveIdx.get(j);
	            		if (Collision.twoShipCollide(shipList.get(m), (ThrustMove) moveList.get(m),
	            				shipList.get(n), (ThrustMove) moveList.get(n))) {
	            			Log.log(String.format("ships %d (thrustmvidx %d mlidx %d) and %d (thrustmvidx %d mlidx %d) may collide", 
	            					shipList.get(m).getId(), i, m,
	            					shipList.get(n).getId(), j, n));
	            			collisionSets.union(i, j);
	            		}
	            	}
	            }
	            
	            HashSet<Integer> parents = new HashSet<>(); // find the non-singleton sets in the disjoint set
	            for (int i = 0; i < thrustMoveIdx.size(); i++) {
	            	if (i != collisionSets.find(i)) {
	            		parents.add(collisionSets.find(i));
	            	}
	            }
	            ArrayList<Integer> parents2 = new ArrayList<Integer>(parents);
	            for (Integer i : parents2) {
	            	ArrayList<Integer> group = new ArrayList<>();
	            	for (int j = 0; j < thrustMoveIdx.size(); j++) {
	            		if (collisionSets.find(j) == i) {
	            			group.add(thrustMoveIdx.get(j)); // get the moveList indices to put into collisionGroups
	            		}
	            	}
	            	collisionGroups.add(new ArrayList<Integer>(group));
	            }
//            } else {
//            	collisionGroups.add(thrustMoveIdx);
//            }
//            if (!collisionGroups.isEmpty()) {
//            	Log.log(thrustMoveIdx.toString());
//            	Log.log(collisionGroups.toString());
//            }
            
            // fix collisions (this is kind of hacky)
            for (ArrayList<Integer> cg : collisionGroups) {
            	if (cg.size() >= 2) {
	            	Log.log(cg.toString());
	            	ArrayList<Move> revisedMoves = new ArrayList<>();
	            	ArrayList<ThrustMove> cMoves = new ArrayList<>();
	            	ArrayList<Ship> cShipList = new ArrayList<>();
	            	for (Integer i : cg) {
	            		Log.log(moveList.get(i).toString());
	            		cMoves.add((ThrustMove) moveList.get(i));
	            		cShipList.add(shipList.get(i));
	            	}
	            	Log.log(cg.toString());
	            	revisedMoves = Navigation.reviseMovesCollision_v2(gameMap, cMoves, cShipList);
	            	for (int i = 0; i < cg.size(); i++) {
	                	moveList.set(cg.get(i), revisedMoves.get(i));
	                }
            	}
            }
            
            Networking.sendMoves(moveList);
        }
    }
}

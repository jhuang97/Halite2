import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.TreeSet;

import hlt2.Collision;
import hlt2.Constants;
import hlt2.DisjointSet;
import hlt2.DockMove;
import hlt2.GameMap;
import hlt2.Log;
import hlt2.Move;
import hlt2.Navigation;
import hlt2.Networking;
import hlt2.Planet;
import hlt2.Position;
import hlt2.Ship;
import hlt2.ThrustMove;

public class MatrixBot3 {
	
	private final String botName = "MatrixBot3"; // MatrixBot2 plus QueueBot-style collision avoidance
	private final Networking networking;
	private final GameMap gameMap;
	private final String initialMapIntelligence;
	
	private final double threatParam, threatParam2;
	
	private final HashMap<Integer, Double> planetValuations = new HashMap<>();
	private final ArrayList<Task> taskList = new ArrayList<>();
	private final ArrayList<PlanetTask> planetTaskList = new ArrayList<>();
	private PlanetTask tmpPlanetTask;
	
    // list the hostile ships near each planet
	private final HashMap<Integer, Map<Double, Ship>> plHostiles = new HashMap<>(); 
    private final HashMap<Integer, Map<Double, Ship>> plUdHostiles = new HashMap<>();
    private final HashMap<Integer, Integer> numplHostiles = new HashMap<>();
    private final HashMap<Integer, Integer> numplUdHostiles = new HashMap<>();
    private final HashMap<Integer, Integer> numplDHostiles = new HashMap<>();
    
    private final ArrayList<Ship> availableShips = new ArrayList<>();
    
    private final HashMap<Integer, Integer> aShipIndices = new HashMap<>();
    private int[] aShipIds;
    private final ArrayList<Integer> remainingShipIndices = new ArrayList<>();
    // contains indices, not ship ids, i.e., the kind of numbers in aShipIndices
    
    private final ArrayList<Integer> remainingTaskIndices = new ArrayList<>();
    
    private double[][] distances;
    private double[][] shipTaskCosts;
    private final HashMap<Integer, Integer> numShipsAssignedToGoDock = new HashMap<>();
    private int[] numShipsForTask;
    private final HashMap<Integer, ArrayList<Ship>> taskAssignments = new HashMap<>();
    
	private final ArrayList<Move> moveList = new ArrayList<>();
	
    public static void main(final String[] args) {
    	MatrixBot3 myBot = new MatrixBot3();
        for (;;) {
        	myBot.performOneMove();
        }
    }
    
    private MatrixBot3() {
        networking = new Networking();
        gameMap = networking.initialize(botName);
        aShipIds = null;
        distances = null;
        shipTaskCosts = null;
        numShipsForTask = null;
        tmpPlanetTask = null;

        int numPlayers = gameMap.getAllPlayers().size();
        
        // We now have 1 full minute to analyze the initial map.
        initialMapIntelligence =
                "width: " + gameMap.getWidth() +
                "; height: " + gameMap.getHeight() +
                "; players: " + numPlayers +
                "; planets: " + gameMap.getAllPlanets().size();
        Log.log(initialMapIntelligence);
        
        
        threatParam = (numPlayers == 2) ? 1.1 : 10;
        threatParam2 = Constants.SHIP_RADIUS + Constants.DOCK_RADIUS + Constants.WEAPON_RADIUS +
        		((numPlayers == 2) ? 1.0 : 0.2) * Constants.DOCK_TURNS * Constants.MAX_SPEED;     	
    }

    private void performOneMove() {
    	networking.updateMap(gameMap);
    	taskAssignments.clear();
    	moveList.clear();
    	remainingShipIndices.clear();
    	remainingTaskIndices.clear();
    	
    	listAvailableShips();
    	listHostileShips();
    	valueAllPlanets();
    	makeTasks();
//    	Log.log(taskList.size() + " ");
    	if (!taskList.isEmpty() && !availableShips.isEmpty()) {
    		calcDists();
    		calcCosts();
    		assignTasks();
    		makeMoves();
    	}
    	dealWithCollisions();
    	
    	Networking.sendMoves(moveList);
    }
    
    private void listAvailableShips() {
    	availableShips.clear();
		for (final Ship ship : gameMap.getMyPlayer().getShips().values()) {
			if (ship.getDockingStatus() == Ship.DockingStatus.Undocked) {
				availableShips.add(ship);
			}
		}
		String s = "";
		for (Ship ship : availableShips) s += ship.getId() + ", ";
		Log.log("ships " + s);
    }
    
    private void listHostileShips() {
        plHostiles.clear();
        plUdHostiles.clear();
        numplHostiles.clear();
        numplUdHostiles.clear();
        numplDHostiles.clear();
        
        for (final Planet planet : gameMap.getAllPlanets().values()) {
        	Map<Double, Ship> aMap = gameMap.hostilesNearPlanet(planet);
        	Map<Double, Ship> bMap = gameMap.undockedHostilesNearPlanet(planet);
        	
        	plHostiles.put(planet.getId(), aMap);
        	numplHostiles.put(planet.getId(), aMap.size());
        	plUdHostiles.put(planet.getId(), bMap);
        	numplUdHostiles.put(planet.getId(), bMap.size());
        	numplDHostiles.put(planet.getId(), aMap.size() - bMap.size());
        }
    }
    
    private void valueAllPlanets() {
    	planetValuations.clear();
    	for (final Planet p : gameMap.getAllPlanets().values()) {
    		double value = 0;
    		if (p.getOwner() == gameMap.getMyPlayerId()) {
            	Map<Double, Ship> nh = plHostiles.get(p.getId()); 
    			if (!nh.isEmpty()) {
    				value = (10 + Math.sqrt((double) nh.size())); // highly sus
    			} else {
    				value = 300;
    			}
    		} else {
    	        int numUdHostiles = numplUdHostiles.get(p.getId());
    	        int numDHostiles = numplDHostiles.get(p.getId());
    	        value = (((double) numUdHostiles)*0.3 + ((double) numDHostiles)*0.2 + 0.4);
    		}
    		planetValuations.put(p.getId(), value);
    	}
    }
    
    static enum TaskType {
    	DOCK_PLANET, // dock at a planet
    	KILL_DOCKED_ENEMIES, // go to an enemy planet to kill the enemies docked to it
    	KILL_ATTACKING_ENEMIES, // defending a planet we already own by attacking a specific hostile ship
    	DEFEND_FRIENDLY_PLANET, // defending a planet we already own by getting between the ships docked to the planet and an incoming ship
    							// this deals with enemy ships that are farther away and might attack
    	DEFEND_NEUTRAL_PLANET, // going to a currently neutral planet with the intention to preemptively to defend it (after it is docked) from enemies
    	TRAVEL_DOCK_PLANET // going to a currently not-fully-docked-by-us (friendly or neutral or enemy) planet with the intention to dock to it 
    }
    
    static class PlanetTask {
    	Planet planet;
    	HashMap<TaskType, ArrayList<Ship>> subTasks;
    	
    	public PlanetTask(Planet planet) {
    		this.planet = planet;
    		subTasks = new HashMap<>();
    	}
    	
    	public PlanetTask(PlanetTask o) {
    		this.planet = o.planet;
    		this.subTasks = new HashMap<>(o.subTasks);
    	}
    	
    	void addSubTask(Task t) {
    		if (subTasks.containsKey(t.taskType)) {
    			if (t.targetShip != null) {
    				subTasks.get(t.taskType).add(t.targetShip);
    			}
    		} else {
    			if (t.targetShip == null) {
    				subTasks.put(t.taskType, null);
    			} else {
    				subTasks.put(t.taskType, new ArrayList<Ship>(Arrays.asList(t.targetShip)));
    			}
    		}
    	}
    }
    
    static class Task {
    	public Task(TaskType taskType, Planet planet, Ship targetShip) {
			this.taskType = taskType;
			this.planet = planet;
			this.targetShip = targetShip;
		}
		TaskType taskType;
    	Planet planet; // the planet to defend/conquer/dock. sometimes null
    	Ship targetShip; // the ship that this ship should travel towards to fight or defend against. sometimes null
		@Override
		public String toString() {
			String s = taskType + " planet " + planet.getId();
			if (targetShip != null) {
				s += " targetShip " + targetShip.getId();
			}
			return s;
		}
    }
    
    private void makeTasks() {
    	taskList.clear();
    	planetTaskList.clear();
    	for (final Planet p : gameMap.getAllPlanets().values()) {
    		tmpPlanetTask = new PlanetTask(p);
    		boolean fullyDockedByUs = (p.getOwner() == gameMap.getMyPlayerId()) && p.isFull();
    		boolean notOwnedByEnemy = !p.isOwned() || p.getOwner() == gameMap.getMyPlayerId();
    		HashMap<Double, Ship> hostilesNearPlanet = new HashMap<Double, Ship>(plHostiles.get(p.getId()));
    		
    		if (!fullyDockedByUs && notOwnedByEnemy) {
    			for (Ship s : availableShips) {
    				if (s.withinDockingRange(p) && hostilesNearPlanet.isEmpty()) {
    					addTask(new Task(TaskType.DOCK_PLANET, p, null));
    					break;
    				}
    			}
    			addTask(new Task(TaskType.TRAVEL_DOCK_PLANET, p, null));
    		}
    		
    		if (p.isOwned() && p.getOwner() != gameMap.getMyPlayerId()) {
    			for (Integer id : p.getDockedShips()) {
    				addTask(new Task(TaskType.KILL_DOCKED_ENEMIES, p, gameMap.getShip(p.getOwner(), id)));
    			}
    		}
    		
    		if (p.getOwner() == gameMap.getMyPlayerId()) {
    			for (Ship enemyShip : hostilesNearPlanet.values()) {
    				addTask(new Task(TaskType.KILL_ATTACKING_ENEMIES, p, enemyShip));
    			}
    			
    			Map<Double, Ship> shipsThatCanThreaten = gameMap.hostilesNearPlanet(p, threatParam2);
    			if (shipsThatCanThreaten.size() > 0) {
    				addTask(new Task(TaskType.DEFEND_FRIENDLY_PLANET, p, null));
    			}
    		}
    		
    		if (!p.isOwned()) addTask(new Task(TaskType.DEFEND_NEUTRAL_PLANET, p, null));
    		
    		if (!tmpPlanetTask.subTasks.isEmpty())
    			planetTaskList.add(new PlanetTask(tmpPlanetTask));
    	}
//    	Log.log(taskList.toString());
    	for (PlanetTask pt : planetTaskList) {
    		Log.log("tasks - planet " + pt.planet.getId() + ": " + pt.subTasks.keySet());
    	}
    }
    
    private void addTask(Task t) {
    	taskList.add(t);
    	tmpPlanetTask.addSubTask(t);
    }
    
    private void calcDists() {
    	// assign indices to the ships
    	aShipIndices.clear();
    	aShipIds = new int[availableShips.size()];
    	int idx = 0;
    	for (Ship ship : availableShips) { // availableShips.get(idx) corresponds to aShipIdx[idx]
    		aShipIndices.put(ship.getId(), idx);
    		aShipIds[idx] = ship.getId();
    		idx++;
    	}
    	
    	// the tasks have the same indices as in taskList
    	distances = new double[taskList.size()][availableShips.size()];
    	for (int asIdx = 0; asIdx < availableShips.size(); asIdx++) {
    		Ship s = availableShips.get(asIdx);
    		for (int tIdx = 0; tIdx < taskList.size(); tIdx++) {
    			double dist = 0;
    			switch (taskList.get(tIdx).taskType)
    			{
    			case DOCK_PLANET:
    			case DEFEND_FRIENDLY_PLANET:
    			case DEFEND_NEUTRAL_PLANET:
    			case TRAVEL_DOCK_PLANET:
    				Planet p = taskList.get(tIdx).planet;
    				dist = p.getDistanceTo(s) - p.getRadius();
    				break;
    			case KILL_DOCKED_ENEMIES:
    			case KILL_ATTACKING_ENEMIES:
    				Ship targetShip = taskList.get(tIdx).targetShip;
    				dist = s.getDistanceTo(targetShip) - s.getRadius();
    				break;
    			}
    			distances[tIdx][asIdx] = dist;
    		}
    	}
//    	Log.log(Arrays.deepToString(distances));
    }
    
    private void calcCosts() { // naive
    	shipTaskCosts = new double[distances.length][distances[0].length];
    	for (int tIdx = 0; tIdx < taskList.size(); tIdx++) {
    		double planetValue = planetValuations.get(taskList.get(tIdx).planet.getId());
    		for (int asIdx = 0; asIdx < availableShips.size(); asIdx++) {
    			shipTaskCosts[tIdx][asIdx] = distances[tIdx][asIdx] / planetValue;
    		}
    	}
    }
    
    private void assignTasks() { // not legit
    	numShipsAssignedToGoDock.clear();
    	numShipsForTask = new int[taskList.size()];
    	
    	// initially, all ships in availableShip have not been assigned to a task,
    	// so all are still in remainingShipIndices.  As the ships are assigned to tasks,
    	// they are removed from remainingShipIndices.
    	for (int i = 0; i < availableShips.size(); i++) remainingShipIndices.add(i);
    	for (int i = 0; i < taskList.size();       i++) remainingTaskIndices.add(i);
    	
//    	HashSet<Integer> nearestPlanets = listNearestPlanetsIds(remainingShipIndices, remainingTaskIndices); // not as useful as I thought it would be
//    	selectTasksOfNearestPlanets(nearestPlanets);
    	for (Task t : taskList) numShipsAssignedToGoDock.put(t.planet.getId(), 0);
    	
    	assignDockingTasks();
    	// TODO: not dock when going to get rushed.
    	
    	// assign travel-dock-planet tasks and kill-docked-enemies tasks
    	// (not entirely clear how to split this algorithmically)
    	// do shortest distance for now -- need to fix
    	ArrayList<Integer> altRemTaskIndices = filterTaskIndicesForTravelDock();
    	Log.log("task indices for travel dock: " + remainingTaskIndices.toString());
    	
    	// TRAVEL_DOCK_PLANET needs to be competing with some other tasks
    	// need to figure out which ships are close enough to a planet to be assigned this
    	
    	assignTravelDockingTasks(altRemTaskIndices);

//    	for (Integer t: remTaskIndices) {
//    		Log.log(t + ": " + Arrays.toString(distances[t]));
//    	}
    	
//    	listNearestPlanetsIds(remainingShipIndices, remainingTaskIndices);
    	
    	filterTaskIndicesAfterTravelDock();
    	
    	for (Integer i : remainingShipIndices) {
    		double minDist = Double.MAX_VALUE;
    		int minTIdx = -1;
    		for (Integer t : remainingTaskIndices) {
    			if (distances[t][i] < minDist) {
    				minDist = distances[t][i];
    				minTIdx = t;
    			}
    		}
    		if (minTIdx != -1)
    			assignShipToTask(availableShips.get(i), minTIdx);
    	}
    	Log.log("number of assignments: " + taskAssignments.size());
    }
    
    private void assignShipToTask(Ship s, int taskIdx) {
    	if (taskAssignments.containsKey(taskIdx)) {
    		taskAssignments.get(taskIdx).add(s);
    	} else {
    		taskAssignments.put(taskIdx, new ArrayList<>(Arrays.asList(s)));
    	}
    	numShipsForTask[taskIdx]++;
    	Task t = taskList.get(taskIdx);
    	if (t.taskType == TaskType.DOCK_PLANET || t.taskType == TaskType.TRAVEL_DOCK_PLANET) {
    		int pId = t.planet.getId();
    		numShipsAssignedToGoDock.put(pId, numShipsAssignedToGoDock.get(pId)+1);
    	}
    }
    
    private void selectTasksOfNearestPlanets(HashSet<Integer> nearestPlanetIds) {
    	Iterator<Integer> it = remainingTaskIndices.iterator();
    	while (it.hasNext()) {
    		Task t = taskList.get(it.next());
    		if (!nearestPlanetIds.contains(t.planet.getId())) {
    			it.remove();
    		}
    	}
    }
    
    private void assignDockingTasks() {
    	// assign docking tasks; naively assume that anything in docking range ought to dock
    	ArrayList<Integer> dockTaskIdx = new ArrayList<>();
    	ArrayList<Integer> numShipsNeededForDocking = new ArrayList<>();
    	
    	Iterator<Integer> it = remainingTaskIndices.iterator();
    	while (it.hasNext()) {
    		int i = it.next();
    		if (taskList.get(i).taskType == TaskType.DOCK_PLANET) {
    			Planet p = taskList.get(i).planet;
    			dockTaskIdx.add(i);
    			it.remove();
    			numShipsNeededForDocking.add(p.getDockingSpots() - p.getDockedShips().size());
    		}
    	}
//    	Log.log(dockTaskIdx.toString());
    	for (int i = 0; i < dockTaskIdx.size(); i++) {
    		int dtIdx = dockTaskIdx.get(i);
    		Iterator<Integer> it2 = remainingShipIndices.iterator();
    		while (it2.hasNext() && numShipsForTask[dtIdx] < numShipsNeededForDocking.get(i)) {
    			Ship ship = availableShips.get(it2.next());
    			if (ship.withinDockingRange(taskList.get(dtIdx).planet)) {
	    			assignShipToTask(ship, dtIdx);
	    			it2.remove();
    			}
    		}
    	}
    }
    
    // picks only the tasks that we can handle
    private ArrayList<Integer> filterTaskIndicesForTravelDock() {
    	ArrayList<Integer> altRemTaskIndices = new ArrayList<>(remainingTaskIndices);
    	altRemTaskIndices.removeIf(i -> !taskFilter(taskList.get(i)));
    	return altRemTaskIndices;
    }
    
    private boolean taskFilter(Task t) {
    	Planet p = t.planet;
    	TaskType tt = t.taskType;
    	switch (tt) {
    	case KILL_DOCKED_ENEMIES:
    	case KILL_ATTACKING_ENEMIES:
    		return true;
    	case TRAVEL_DOCK_PLANET:
    		if (!p.isOwned() || p.getOwner() == gameMap.getMyPlayerId()) return true;
    		break;
		default:
			return false;    		
    	}
    	return false;
    }
    
    private void filterTaskIndicesAfterTravelDock() {
    	remainingTaskIndices.removeIf(i -> !taskFilter2(taskList.get(i)));
    }
    
    private boolean taskFilter2(Task t) {
    	Planet p = t.planet;
    	TaskType tt = t.taskType;
    	switch (tt) {
    	case KILL_DOCKED_ENEMIES:
    	case KILL_ATTACKING_ENEMIES:
    		return true;
		default:
			return false;    		
    	}
    }
    
    private void assignTravelDockingTasks(ArrayList<Integer> altTaskIndices) {
    	// each loop assigns one ship to travel docking until no ships want to travel dock
    	while (true) {
	    	// remove all travel docking tasks that already have enough ships
	    	Iterator<Integer> it = altTaskIndices.iterator();
	    	while (it.hasNext()) {
	    		int tIdx = it.next();
	    		Task t = taskList.get(tIdx);
	    		if (t.taskType == TaskType.TRAVEL_DOCK_PLANET) {
	    			Planet p = t.planet;
	    			if (p.getDockingSpots() <= 
	    					p.getDockedShips().size() + numShipsAssignedToGoDock.get(p.getId())) {
	    				it.remove();
	    			}
	    		}
	    	}
	    	
	    	// See which ships would prefer to (shortest dist/lowest cost) TRAVEL_DOCK.
	    	// Assign the ship with the smallest distance to actually TRAVEL_DOCK
	    	double minShipDist = Double.MAX_VALUE;
	    	int minSIdx = -1;
	    	int overall_minTIdx = -1;
	    	for (Integer sIdx : remainingShipIndices) {
	    		double minDist = Double.MAX_VALUE;
	    		int minTIdx = -1;
	    		for (Integer t : altTaskIndices) {
	    			// find the lowest cost/nearest task for the ship; store in minDist, minTIdx
	    			if (distances[t][sIdx] < minDist) {
	    				minDist = distances[t][sIdx];
	    				minTIdx = t;
	    			}
	    		}
	    		if (minTIdx != -1 && taskList.get(minTIdx).taskType == TaskType.TRAVEL_DOCK_PLANET) {
	    			Ship s = availableShips.get(sIdx);
	    			Planet p = taskList.get(minTIdx).planet;
	    			if (!s.withinDockingRange(p)) { // prohibit ships already within docking range from travel-docking
		    			// actually consider this sIdx, minTIdx, and minDist
		    			if (minDist < minShipDist) {
		    				minShipDist = minDist;
		    				minSIdx = sIdx;
		    				overall_minTIdx = minTIdx;
		    			}
	    			}
	    		}
	    	}
	    	if (minSIdx != -1) {
	    		assignShipToTask(availableShips.get(minSIdx), overall_minTIdx);
	    		remainingShipIndices.remove(Integer.valueOf(minSIdx));
	    	} else {
	    		return;
	    	}
    	}
    }
    
    // returns ids of nearest planets
    private HashSet<Integer> listNearestPlanetsIds(ArrayList<Integer> shipIndices, ArrayList<Integer> taskIndices) {
    	TreeSet<Integer> nearPlanets = new TreeSet<>();
    	for (Integer i : shipIndices) {
    		double minDist = Double.MAX_VALUE;
    		int minTIdx = -1;
    		for (Integer t : taskIndices) {
    			if (distances[t][i] < minDist) {
    				minDist = distances[t][i];
    				minTIdx = t;
    			}
    			
    			// also explicitly check for docking range,
    			// in case a ship is within docking range of two planets
    			Ship s = availableShips.get(i);
    			Planet p = taskList.get(t).planet;
    			if (s.withinDockingRange(p)) nearPlanets.add(p.getId());
    		}
    		if (minTIdx != -1) {
    			nearPlanets.add(taskList.get(minTIdx).planet.getId());
    		}
    	}
//    	Log.log("nearest planets: " + nearPlanets);
    	return new HashSet<Integer>(nearPlanets);
    }
    
    private void makeMoves() {
    	for (Entry<Integer, ArrayList<Ship>> e : taskAssignments.entrySet()) {
    		Task t = taskList.get(e.getKey());
			Planet p = t.planet;
			Ship targetShip = t.targetShip;
    		String shipStr = "";
    		for (Ship s : e.getValue()) shipStr += s.getId() + ", ";
    		Log.log(t + ", ships " + shipStr);
    		for (Ship ship : e.getValue()) {	
    			switch (t.taskType) {
    			case DOCK_PLANET: 
    				moveList.add(new DockMove(ship, p)); break;
    			case KILL_DOCKED_ENEMIES:
    			case KILL_ATTACKING_ENEMIES:
    				moveList.add(
    						Navigation.navShipToHostileShip(gameMap, ship, targetShip, Constants.MAX_SPEED));
    				break;
    			case TRAVEL_DOCK_PLANET:
    				moveList.add(
    						Navigation.navShipToDock(gameMap, ship, p, Constants.MAX_SPEED));
    				break;
				default:
					break;
    			}
    		}
    	}
    }
    
    private void dealWithCollisions() {
    	ArrayList<Ship> shipList = new ArrayList<>();
    	for (Move m : moveList) shipList.add(m.getShip());
    	
        // detect collisions
        ArrayList<ArrayList<Integer>> collisionGroups = new ArrayList<>();
        ArrayList<Integer> thrustMoveIdx = new ArrayList<>();
    	// Things are messy here.  We have thrustMove indices and moveList indices.
        for (int i = 0; i < moveList.size(); i++) {
        	if (moveList.get(i) instanceof ThrustMove) {
        		thrustMoveIdx.add(i);
        	}
        }

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
//        } else {
//        	collisionGroups.add(thrustMoveIdx);
//        }
//        if (!collisionGroups.isEmpty()) {
//        	Log.log(thrustMoveIdx.toString());
//        	Log.log(collisionGroups.toString());
//        }
        
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
            	revisedMoves = Navigation.reviseMovesCollision(gameMap, cMoves, cShipList);
            	for (int i = 0; i < cg.size(); i++) {
                	moveList.set(cg.get(i), revisedMoves.get(i));
                }
        	}
        }        
    }
}

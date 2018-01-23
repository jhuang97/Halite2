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
import hlt2.Ship.DockingStatus;
import hlt2.ThrustMove;
import hlt2.Entity;

public class MatrixBot6 {
	
	private final String botName = "MatrixBot6"; // better navigation? better collision detection?
	
    private void performOneMove() {
    	networking.updateMap(gameMap);
    	taskAssignments.clear();
    	shipPlanetAssignments.clear();
    	prevRushShipPlanetAssignments.clear();
    	prevRushShipPlanetAssignments.putAll(rushShipPlanetAssignments);
    	rushShipPlanetAssignments.clear();
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
    		regroupAsNeeded();
    	}
    	dealWithCollisions();
    	
    	Networking.sendMoves(moveList);
    }
	
	private final Networking networking;
	private final GameMap gameMap;
	private final String initialMapIntelligence;
	private final int numPlayers;
	
	private final double threatParam, threatParam2;
	private final double planetDefenseRange;
	
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
	private final HashMap<Integer, Map<Double, Ship>> plThreateningHostiles = new HashMap<>();

    
    private final ArrayList<Ship> availableShips = new ArrayList<>();
    
    private final ArrayList<Ship> myStillShips = new ArrayList<>();
    
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
    private final HashMap<Integer, Integer> shipPlanetAssignments = new HashMap<>();
    private final HashMap<Integer, Integer> prevRushShipPlanetAssignments = new HashMap<>(); // the planet that the ship couldn't dock at
    private final HashMap<Integer, Integer> rushShipPlanetAssignments = new HashMap<>();
    
	private final ArrayList<Move> moveList = new ArrayList<>();
	
    public static void main(final String[] args) {
    	MatrixBot6 myBot = new MatrixBot6();
        for (;;) {
        	myBot.performOneMove();
        }
    }
    
    private MatrixBot6() {
        networking = new Networking();
        gameMap = networking.initialize(botName);
        gameMap.setMyStillShips(myStillShips);
        aShipIds = null;
        distances = null;
        shipTaskCosts = null;
        numShipsForTask = null;
        tmpPlanetTask = null;

        numPlayers = gameMap.getAllPlayers().size();
        
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
        planetDefenseRange = threatParam2 + Constants.MAX_SPEED;
    }
    
    private void listAvailableShips() {
    	availableShips.clear();
    	myStillShips.clear();
    	
		for (final Ship ship : gameMap.getMyPlayer().getShips().values()) {
			if (ship.getDockingStatus() == Ship.DockingStatus.Undocked) {
				availableShips.add(ship);
			} else {
				myStillShips.add(ship);
			}
		}
		if (!availableShips.isEmpty()) {
			String s = "";
			for (Ship ship : availableShips) s += ship.getId() + ", ";
			Log.log("ships " + s);
		}
    }
    
    private void listHostileShips() {
        plHostiles.clear();
        plUdHostiles.clear();
        numplHostiles.clear();
        numplUdHostiles.clear();
        numplDHostiles.clear();
        plThreateningHostiles.clear();
        
        for (final Planet planet : gameMap.getAllPlanets().values()) {
        	Map<Double, Ship> aMap = gameMap.hostilesNearPlanet(planet);
        	Map<Double, Ship> bMap = gameMap.undockedHostilesNearPlanet(planet);
        	
        	plHostiles.put(planet.getId(), aMap);
        	numplHostiles.put(planet.getId(), aMap.size());
        	plUdHostiles.put(planet.getId(), bMap);
        	numplUdHostiles.put(planet.getId(), bMap.size());
        	numplDHostiles.put(planet.getId(), aMap.size() - bMap.size());
        	
    		plThreateningHostiles.put(planet.getId(), gameMap.hostilesNearPlanet(planet, threatParam2));
        }
    }
    
    private void valueAllPlanets() {
    	planetValuations.clear();
    	for (final Planet p : gameMap.getAllPlanets().values()) {
    		double value = 0;
    		if (p.getOwner() == gameMap.getMyPlayerId()) {
            	Map<Double, Ship> nh = plHostiles.get(p.getId()); 
    			if (!nh.isEmpty()) {
    				value = 1.1; // highly sus
    			} else {
    				value = 0.9;
    			}
    		} else {
    	        int numUdHostiles = numplUdHostiles.get(p.getId());
    	        int numDHostiles = numplDHostiles.get(p.getId());
    	        value = Math.pow(0.96, numUdHostiles);
    	        if (numPlayers == 2 ) {
    	        	value *= Math.pow(1.05, numDHostiles);
    	        } else {
    	        	value *= Math.pow(0.96, numDHostiles);
    	        }
    		}
    		planetValuations.put(p.getId(), value);
    	}
    }
    
    static enum TaskType {
    	DOCK_PLANET, // dock at a planet
    	TRAVEL_DOCK_PLANET, // going to a currently not-fully-docked-by-us (friendly or neutral or enemy) planet with the intention to dock to it
    	KILL_DOCKED_ENEMIES, // go to an enemy planet to kill the enemies docked to it
    	RUSH_KILL_DOCKED_ENEMIES, // like above, but in a hurry
    	KILL_ENEMIES_NEAR_FRIENDLY_PLANET, // defending a planet we already own by attacking a specific hostile ship
    	DEFEND_FRIENDLY_PLANET, // defending a planet we already own by getting between the ships docked to the planet and an incoming ship
    							// this deals with enemy ships that are farther away and might attack
    	KILL_ENEMIES_NEAR_NEUTRAL_PLANET, // defending a planet nobody owns by a attacking a specific hostile ship
    	DEFEND_NEUTRAL_PLANET, // going to a currently neutral planet with the intention to preemptively to defend it (after it is docked) from enemies
    	STILL,                 // do nothing
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
			String s = taskType.toString();
			if (planet != null) s += " planet " + planet.getId();
			if (targetShip != null) {
				s += " targetShip " + targetShip.getId();
			}
			return s;
		}
    }
    
    private void makeTasks() {
    	taskList.clear();
    	planetTaskList.clear();
    	
    	taskList.add(new Task(TaskType.STILL, null, null));
    	
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
    				if (numPlayers == 2) {
    					addTask(new Task(TaskType.RUSH_KILL_DOCKED_ENEMIES, p, gameMap.getShip(p.getOwner(), id)));
    				}
    			}
    		}
    		
			Map<Double, Ship> shipsToDefendAgainst = gameMap.hostilesNearPlanet(p, planetDefenseRange);
    		if (p.getOwner() == gameMap.getMyPlayerId()) {
    			for (Ship enemyShip : hostilesNearPlanet.values()) {
    				addTask(new Task(TaskType.KILL_ENEMIES_NEAR_FRIENDLY_PLANET, p, enemyShip));
    			}
    			if (shipsToDefendAgainst.size() > 0) {
    				for (Entry<Double, Ship> e : shipsToDefendAgainst.entrySet()) {
    					if (!hostilesNearPlanet.containsValue(e.getValue())) {
    						addTask(new Task(TaskType.DEFEND_FRIENDLY_PLANET, p, e.getValue()));
    					}
    				}
    			}
    		} else if (!p.isOwned()) {
    			for (Ship enemyShip : hostilesNearPlanet.values()) {
    				addTask(new Task(TaskType.KILL_ENEMIES_NEAR_NEUTRAL_PLANET, p, enemyShip));
    			}
    			if (shipsToDefendAgainst.size() > 0) {
    				for (Entry<Double, Ship> e : shipsToDefendAgainst.entrySet()) {
    					if (!hostilesNearPlanet.containsValue(e.getValue())) {
    						addTask(new Task(TaskType.DEFEND_NEUTRAL_PLANET, p, e.getValue()));
    					}
    				}
    			}
    		}
    		
    		if (!tmpPlanetTask.subTasks.isEmpty())
    			planetTaskList.add(new PlanetTask(tmpPlanetTask));
    	}
//    	Log.log(taskList.toString());
//    	for (PlanetTask pt : planetTaskList) {
//    		Log.log("tasks - planet " + pt.planet.getId() + ": " + pt.subTasks.keySet());
//    	}
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
    			Planet p = taskList.get(tIdx).planet;
    			Ship targetShip = taskList.get(tIdx).targetShip;
    			switch (taskList.get(tIdx).taskType)
    			{
    			case DEFEND_FRIENDLY_PLANET:
    			case DEFEND_NEUTRAL_PLANET:
    				dist += 0.3*p.getDistanceTo(targetShip);
    			case DOCK_PLANET:
    			case TRAVEL_DOCK_PLANET:
    				dist += p.getDistanceTo(s) - p.getRadius();
    				break;
    			case KILL_DOCKED_ENEMIES:
    			case RUSH_KILL_DOCKED_ENEMIES:
    			case KILL_ENEMIES_NEAR_FRIENDLY_PLANET:
    			case KILL_ENEMIES_NEAR_NEUTRAL_PLANET:
    				dist = s.getDistanceTo(targetShip) - s.getRadius();
    				break;
    			case STILL:
    				dist = gameMap.getHeight() + gameMap.getHeight();
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
    		Planet p = taskList.get(tIdx).planet;
    		double planetValue = (p != null) ? planetValuations.get(p.getId()) : 1;
    		for (int asIdx = 0; asIdx < availableShips.size(); asIdx++) {
    			shipTaskCosts[tIdx][asIdx] = distances[tIdx][asIdx] / planetValue;
    		}
    	}
    }
    
    private void assignTasks() { // not legit
    	numShipsAssignedToGoDock.clear();
    	numShipsForTask = new int[taskList.size()];
    	
//    	for (int i = 0; i < taskList.size(); i++) {
//    		Task t = taskList.get(i);
//    		Log.log(i + " " + t.taskType + " planet " + t.planet.getId() + ((t.targetShip != null) ? (" targetShip " + t.targetShip.getId()) : ""));
//    	}
    	
    	// initially, all ships in availableShip have not been assigned to a task,
    	// so all are still in remainingShipIndices.  As the ships are assigned to tasks,
    	// they are removed from remainingShipIndices.
    	for (int i = 0; i < availableShips.size(); i++) remainingShipIndices.add(i);
    	for (int i = 0; i < taskList.size();       i++) remainingTaskIndices.add(i);
    	
    	for (Task t : taskList) {
    		Planet p = t.planet;
    		if (p != null) numShipsAssignedToGoDock.put(p.getId(), 0);
    	}
    	
    	if (numPlayers == 2) {
    		beAggressive();
    	}
    	
    	assignDockingTasks();
    	// TODO: not dock when going to get rushed.
    	
    	// assign travel-dock-planet tasks and kill-docked-enemies tasks
    	// (not entirely clear how to split this algorithmically)
    	// do shortest distance for now -- need to fix
    	ArrayList<Integer> altRemTaskIndices = filterTaskIndicesForTravelDock();
//    	Log.log("task indices for travel dock: " + remainingTaskIndices.toString());
    	
    	// TRAVEL_DOCK_PLANET needs to be competing with some other tasks
    	// need to figure out which ships are close enough to a planet to be assigned this
    	
    	assignTravelDockingTasks(altRemTaskIndices);

//    	for (Integer t: remTaskIndices) {
//    		Log.log(t + ": " + Arrays.toString(distances[t]));
//    	}
    	
//    	listNearestPlanetsIds(remainingShipIndices, remainingTaskIndices);
    	
    	filterTaskIndicesAfterTravelDock();
    	
    	for (Integer i : remainingShipIndices) {
    		double minCost = Double.MAX_VALUE;
    		int minTIdx = -1;
    		for (Integer t : remainingTaskIndices) {
    			if (shipTaskCosts[t][i] < minCost) {
    				minCost = shipTaskCosts[t][i];
    				minTIdx = t;
    			}
    		}
    		if (minTIdx != -1)
    			assignShipToTask(availableShips.get(i), minTIdx);
    	}
    	Log.log("number of assignments: " + taskAssignments.size());
    }
    
	// idea: ships that arrive at a planet and can't dock due to dangerous situation
	// from sufficiently close enemies become reserved for rushing/defending
    private void beAggressive() {
    	ArrayList<Integer> dockablePlanetsInDangerousPositions = new ArrayList<>();
    	for (Planet p : gameMap.getAllPlanets().values()) {
    		int numAvailableFriendlyShips = Math.max(0, gameMap.undockedFriendliesNearPlanet(p).size()
    				- (p.getDockingSpots() - p.getDockedShips().size()));
    		Map<Double, Ship> shipsThatCanThreaten = plThreateningHostiles.get(p.getId());
    		if (shipsThatCanThreaten.size() > threatParam * numAvailableFriendlyShips) {
    			dockablePlanetsInDangerousPositions.add(p.getId());
    		}
    	}
    	
    	Iterator<Integer> it = remainingShipIndices.iterator();
    	while (it.hasNext()) {
    		Ship ship = availableShips.get(it.next());
    		int pRushDefendId = -1;
    		if (gameMap.dockedEnemyShips().size() < 5) {
	    		for (Planet p : gameMap.getAllPlanets().values()) {
	    			if (ship.withinDockingRange(p) && dockablePlanetsInDangerousPositions.contains(p.getId())) {
	    				pRushDefendId = p.getId();
	    			}
	    		}
    		}
    		if (pRushDefendId == -1) {
    			if (prevRushShipPlanetAssignments.containsKey(ship.getId())) {
    				int pId = prevRushShipPlanetAssignments.get(ship.getId());
    				if (dockablePlanetsInDangerousPositions.contains(pId)) {
    					pRushDefendId = pId;
    				}
    			}
    		}
    		if (pRushDefendId != -1) {
	    		Ship nearestDockedEnemy = null;
	    		double minDist = Double.POSITIVE_INFINITY;
	    		for (Entry<Double, Ship> e : plThreateningHostiles.get(pRushDefendId).entrySet()) {
	    			if (e.getValue().getDockingStatus() != DockingStatus.Undocked && e.getKey() < minDist) {
	    				nearestDockedEnemy = e.getValue();
	    				minDist = e.getKey();
	    			}
	    		}
	    		int taskIdx = -1;
//	    		Log.log("rush ship " + ship.getId());
	    		if (nearestDockedEnemy != null) {
//	    			Log.log("attack docked ship " + nearestDockedEnemy.getId() + " at planet " + nearestDockedEnemy.getDockedPlanet());
	    			// figure out task index
//	    			Log.log("task list size " + taskList.size());
		    		for (int i = 0; i < taskList.size() && taskIdx == -1; i++) {
		    			Task t = taskList.get(i);
		    			if (t.taskType == TaskType.RUSH_KILL_DOCKED_ENEMIES
		    					&& t.planet.getId() == nearestDockedEnemy.getDockedPlanet()
		    					&& t.targetShip.getId() == nearestDockedEnemy.getId()) {
		    				taskIdx = i;
		    			}
		    		}
	    		} 
	    		if (taskIdx == -1) { // defend neutral planet
		    		Ship nearestUndockedEnemy = null;
		    		minDist = Double.POSITIVE_INFINITY;
		    		for (Entry<Double, Ship> e : plThreateningHostiles.get(pRushDefendId).entrySet()) {
		    			if (e.getValue().getDockingStatus() == DockingStatus.Undocked && e.getKey() < minDist) {
		    				nearestUndockedEnemy = e.getValue();
		    				minDist = e.getKey();
		    			}
		    		}
	    			
	    			// figure out task index again... ugh.
		    		if (nearestUndockedEnemy != null) {
			    		for (int i = 0; i < taskList.size() && taskIdx == -1; i++) {
//			    			Log.log("defend against ship " + nearestUndockedEnemy.getId() + " for planet " + pRushDefendId);
			    			Task t = taskList.get(i);
			    			if ((t.taskType == TaskType.DEFEND_NEUTRAL_PLANET || t.taskType == TaskType.DEFEND_FRIENDLY_PLANET)
			    					&& t.planet.getId() == pRushDefendId
			    					&& t.targetShip.getId() == nearestUndockedEnemy.getId()) {
			    				taskIdx = i;
			    			}
			    		}
		    		}
	    		}
	    		if (taskIdx != -1) {
	    			assignShipToTask(ship, taskIdx);
	    			rushShipPlanetAssignments.put(ship.getId(), pRushDefendId);
	    			it.remove();
	    		}
    		}
    	}
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
    	
    	if (t.taskType == TaskType.DOCK_PLANET || t.taskType == TaskType.STILL) {
    		myStillShips.add(s);
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
    	altRemTaskIndices.removeIf(i -> !taskFilterForTravelDock(taskList.get(i)));
    	return altRemTaskIndices;
    }
    
    private boolean taskFilterForTravelDock(Task t) {
    	Planet p = t.planet;
    	TaskType tt = t.taskType;
    	switch (tt) {
    	case KILL_DOCKED_ENEMIES:
    	case KILL_ENEMIES_NEAR_FRIENDLY_PLANET:
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
    	remainingTaskIndices.removeIf(i -> !taskFilterAfterTravelDockAssigned(taskList.get(i)));
    }
    
    private boolean taskFilterAfterTravelDockAssigned(Task t) {
    	Planet p = t.planet;
    	TaskType tt = t.taskType;
    	switch (tt) {
    	case KILL_DOCKED_ENEMIES:
    	case KILL_ENEMIES_NEAR_FRIENDLY_PLANET:
//    	case DEFEND_NEUTRAL_PLANET:
    	case STILL:
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
	    			if (shipTaskCosts[t][sIdx] < minDist) {
	    				minDist = shipTaskCosts[t][sIdx];
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
    			if (p != null)
    				shipPlanetAssignments.put(ship.getId(), p.getId());
    			switch (t.taskType) {
    			case DOCK_PLANET: 
    				moveList.add(new DockMove(ship, p)); break;
    			case KILL_DOCKED_ENEMIES:
    			case RUSH_KILL_DOCKED_ENEMIES:
    			case KILL_ENEMIES_NEAR_FRIENDLY_PLANET:
    				moveList.add(
    						Navigation.navShipToHostileShip_v2(gameMap, ship, targetShip, Constants.MAX_SPEED));
    				break;
    			case TRAVEL_DOCK_PLANET:
    				moveList.add(
    						Navigation.navShipToDock_v2(gameMap, ship, p, Constants.MAX_SPEED));
    				break;
    			case DEFEND_NEUTRAL_PLANET:
    			case DEFEND_FRIENDLY_PLANET:
    				Position defPos = Navigation.defensePointAgainstHostileShip(gameMap, 
    						p, targetShip, Constants.MAX_SPEED);
    				moveList.add(Navigation.navShipTowardsTarget_v2(gameMap, 
    						ship, defPos, Constants.MAX_SPEED,
    						true, Constants.MAX_NAVIGATION_CORRECTIONS, Math.PI/180.0));
    				break;
    			case STILL:
    				moveList.add(new ThrustMove(ship, 0, 0));
				default:
					break;
    			}
    		}
    	}
    }
    
//    private ThrustMove neutralPlanetDefenseMove(Ship ship, Planet p) {
//    	
//    }
    
    private void regroupAsNeeded() {
    	ArrayList<Ship> shipList = new ArrayList<>();
    	for (Move m : moveList) shipList.add(m.getShip());
    	
        ArrayList<Integer> thrustMoveIdx = new ArrayList<>();
        for (int i = 0; i < moveList.size(); i++) {
        	if (moveList.get(i) instanceof ThrustMove) {
        		thrustMoveIdx.add(i);
        	}
        }
    	
    	ArrayList<Integer> shipNeedRegroupIdx = new ArrayList<>();
    	for (Integer i : thrustMoveIdx) {
    		Map<Double, Ship> nearbyFriendlies =
    				gameMap.undockedFriendliesNearShip(shipList.get(i), 
    						2*(Constants.MAX_SPEED + Constants.SHIP_RADIUS));
    		boolean alone = true; // alone if there is no one nearby with a thrustMove
    		for (Entry<Double, Ship> e : nearbyFriendlies.entrySet()) {
    			if (e.getKey() < 3.0) {
    				boolean hasThrustMove = false;
    				for (Integer j : thrustMoveIdx) {
    					if (j != i) {
    						if (shipList.get(j).getId() == e.getValue().getId())
    							hasThrustMove = true;
    					}
    				}
    				if (hasThrustMove) alone = false;
    			}
    		}
    		Map<Double, Ship> nearbyHostiles =
    				gameMap.hostilesNearShip(shipList.get(i), 
    						2*(Constants.MAX_SPEED + Constants.SHIP_RADIUS));
    		boolean healthDisadvantage = nearbyHostiles.size() > 1;
    		
    		if (alone && healthDisadvantage) shipNeedRegroupIdx.add(i);
    	}
    	
    	// see if there is some nearby ally with the same target planet as the ship-in-need-of-group
    	HashSet<Integer> regroupedIndices = new HashSet<Integer>();
    	ArrayList<Integer> shipRegroupTargetIdx = new ArrayList<>();
    	for (Integer i : shipNeedRegroupIdx) {
    		if (!shipRegroupTargetIdx.contains(i)) {
	    		Ship aloneShip = shipList.get(i);
	    		boolean regrouped = false;
	    		for (int k = 0; k < thrustMoveIdx.size() && !regrouped; k++) {
	    			int j = thrustMoveIdx.get(k);
	    			if (i != j && !regroupedIndices.contains(j)) {
	    				Ship otherShip = shipList.get(j);
	    				ThrustMove ot = (ThrustMove) moveList.get(j);
	    				if (shipPlanetAssignments.get(aloneShip.getId())
	    						== shipPlanetAssignments.get(otherShip.getId())) {
		    				boolean closeEnoughToGroupWith = aloneShip.getDistanceTo(otherShip)
		    						< 2*(Constants.MAX_SPEED + Constants.SHIP_RADIUS);
		    				Entity otherShipTarget = new Entity(otherShip.getOwner(), otherShip.getId(), 
		    						otherShip.getXPos() + ot.getdX(),
		    						otherShip.getYPos() + ot.getdY(), 
		    						otherShip.getHealth(), otherShip.getRadius());
		    				boolean nothingInBetween = gameMap.objectsBetween(aloneShip, otherShip).isEmpty()
		    						&& gameMap.objectsBetween(aloneShip, otherShipTarget).isEmpty();
		    				if (closeEnoughToGroupWith && nothingInBetween) {
		    					moveList.set(i, Navigation.navShipToDock(gameMap,
		    							aloneShip, otherShipTarget, Constants.MAX_SPEED));
		    					regroupedIndices.add(i);
		    					shipRegroupTargetIdx.add(j);
		    					Log.log("Ship " + aloneShip.getId() + " is regrouping towards ship " + otherShip.getId());
		    				}
	    				}
	    			}
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
        ArrayList<Position> initialPositions = new ArrayList<>();
        ArrayList<Position> finalPositions = new ArrayList<>();
    	// Things are messy here.  We have thrustMove indices and moveList indices.
        for (int i = 0; i < moveList.size(); i++) {
        	Move m = moveList.get(i);
        	if (m instanceof ThrustMove) {
        		thrustMoveIdx.add(i);
        		double xi = m.getShip().getXPos();
        		double yi = m.getShip().getYPos();
        		double xf = xi + ((ThrustMove) m).getdX();
        		double yf = yi + ((ThrustMove) m).getdY();
        		initialPositions.add(new Position(xi, yi));
        		finalPositions.add(new Position(xf, yf));
        	}
        }

            DisjointSet collisionSets = DisjointSet.makeSingletons(thrustMoveIdx.size());
            for (int i = 0; i < thrustMoveIdx.size(); i++) {
            	for (int j = i+1; j < thrustMoveIdx.size(); j++) {
            		if (collisionSets.find(i) != collisionSets.find(j)) {
            			int m = thrustMoveIdx.get(i);
            			int n = thrustMoveIdx.get(j);
            			if (Collision.twoShipCollide(shipList.get(m), (ThrustMove) moveList.get(m),
            					shipList.get(n), (ThrustMove) moveList.get(n))) {
            				Log.log(String.format("ships %d and %d may collide", 
            						shipList.get(m).getId(), i, m,
            						shipList.get(n).getId(), j, n));
            				collisionSets.union(i, j);
            			} else if (initialPositions.get(i).getDistanceTo(initialPositions.get(j)) < 4.0) {
            				Log.log(String.format("ships %d and %d start near each other", 
            						shipList.get(m).getId(), i, m,
            						shipList.get(n).getId(), j, n));
            				collisionSets.union(i, j);
            			} else if (finalPositions.get(i).getDistanceTo(finalPositions.get(j)) < 4.0) {
            				Log.log(String.format("ships %d and %d end near each other", 
            						shipList.get(m).getId(), i, m,
            						shipList.get(n).getId(), j, n));
            				collisionSets.union(i, j);
            			}
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
            	revisedMoves = Navigation.reviseMovesCollision_v2(gameMap, cMoves, cShipList);
            	for (int i = 0; i < cg.size(); i++) {
                	moveList.set(cg.get(i), revisedMoves.get(i));
                }
        	}
        }        
    }
}

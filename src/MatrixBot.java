import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;

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

public class MatrixBot {
	
	private final String botName = "MatrixBot";
	private final Networking networking;
	private final GameMap gameMap;
	private final String initialMapIntelligence;
	
	private final double threatParam, threatParam2;
	
	private final HashMap<Integer, Double> planetValuations;
	private final ArrayList<Task> taskList;
	
    // list the hostile ships near each planet
	private final HashMap<Integer, Map<Double, Ship>> plHostiles = new HashMap<>(); 
    private final HashMap<Integer, Map<Double, Ship>> plUdHostiles = new HashMap<>();
    private final HashMap<Integer, Integer> numplHostiles = new HashMap<>();
    private final HashMap<Integer, Integer> numplUdHostiles = new HashMap<>();
    private final HashMap<Integer, Integer> numplDHostiles = new HashMap<>();
    
    private final ArrayList<Ship> availableShips;
    
    private final HashMap<Integer, Integer> aShipIndices = new HashMap<>();
    private int[] aShipIds;
    private double[][] distances;
    private double[][] shipTaskCosts;
    private int[] numShipsForTask;
    
    private final HashMap<Integer, ArrayList<Ship>> taskAssignments = new HashMap<>();
    
	private final ArrayList<Move> moveList = new ArrayList<>();
	
    public static void main(final String[] args) {
    	MatrixBot myBot = new MatrixBot();
        for (;;) {
        	myBot.performOneMove();
        }
    }
    
    private MatrixBot() {
        networking = new Networking();
        gameMap = networking.initialize(botName);
        planetValuations = new HashMap<>();
        taskList = new ArrayList<>();
        availableShips = new ArrayList<>();
        aShipIds = null;
        distances = null;
        shipTaskCosts = null;
        numShipsForTask = null;

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
    	for (final Planet p : gameMap.getAllPlanets().values()) {
    		boolean fullyDockedByUs = (p.getOwner() == gameMap.getMyPlayerId()) && p.isFull();
    		boolean notHostile = !p.isOwned() || p.getOwner() == gameMap.getMyPlayerId();
    		
    		if (!fullyDockedByUs && notHostile) {
    			for (Ship s : availableShips) {
    				if (s.withinDockingRange(p)) {
    					taskList.add(new Task(TaskType.DOCK_PLANET, p, null));
    					break;
    				}
    			}
    			taskList.add(new Task(TaskType.TRAVEL_DOCK_PLANET, p, null));
    		}
    		
    		if (p.isOwned() && p.getOwner() != gameMap.getMyPlayerId()) {
    			for (Integer id : p.getDockedShips()) {
    				taskList.add(new Task(TaskType.KILL_DOCKED_ENEMIES, p, gameMap.getShip(p.getOwner(), id)));
    			}
    		}
    		
    		if (p.getOwner() == gameMap.getMyPlayerId()) {
    			HashMap<Double, Ship> hostilesNearPlanet = new HashMap<Double, Ship>(plHostiles.get(p.getId()));
    			for (Ship enemyShip : hostilesNearPlanet.values()) {
    				taskList.add(new Task(TaskType.KILL_ATTACKING_ENEMIES, p, enemyShip));
    			}
    			
    			Map<Double, Ship> shipsThatCanThreaten = gameMap.hostilesNearPlanet(p, threatParam2);
    			if (shipsThatCanThreaten.size() > 0) {
    				taskList.add(new Task(TaskType.DEFEND_FRIENDLY_PLANET, p, null));
    			}
    		}
    		
    		if (!p.isOwned()) taskList.add(new Task(TaskType.DEFEND_NEUTRAL_PLANET, p, null));
    	}
    	Log.log(taskList.toString());
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
    	Log.log(Arrays.deepToString(distances));
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
    	numShipsForTask = new int[taskList.size()];
    	ArrayList<Integer> remainingShipIndices = new ArrayList<>();
    		// contains indices, not ship ids, i.e., the kind of numbers in aShipIndices
    	
    	// initially, all ships in availableShip have not been assigned to a task,
    	// so all are still in remainingShipIndices.  As the ships are assigned to tasks,
    	// they are removed from remainingShipIndices.
    	for (int i = 0; i < availableShips.size(); i++) remainingShipIndices.add(i);
    	
    	// assign docking tasks; naively assume that anything in docking range ought to dock
    	ArrayList<Integer> dockTaskIdx = new ArrayList<>();
    	ArrayList<Integer> numShipsNeededForDocking = new ArrayList<>();
    	
    	for (int i = 0; i < taskList.size(); i++) {
    		if (taskList.get(i).taskType == TaskType.DOCK_PLANET) {
    			Planet p = taskList.get(i).planet;
    			dockTaskIdx.add(i);
    			numShipsNeededForDocking.add(p.getDockingSpots() - p.getDockedShips().size());
    		}
    	}
    	Log.log(dockTaskIdx.toString());
    	for (int i = 0; i < dockTaskIdx.size(); i++) {
    		int dtIdx = dockTaskIdx.get(i);
    		Iterator<Integer> it = remainingShipIndices.iterator();
    		while (it.hasNext() && numShipsForTask[dtIdx] < numShipsNeededForDocking.get(i)) {
    			Ship ship = availableShips.get(it.next());
    			if (ship.withinDockingRange(taskList.get(dtIdx).planet)) {
	    			assignShipToTask(ship, dtIdx);
	    			it.remove();
    			}
    		}
    	}
    	
    	ArrayList<Integer> remTaskIndices = remainingTaskIndices();
    	Log.log("remaining task indices: " + remTaskIndices.toString());
    	
    	// assign travel-dock-planet tasks and kill-docked-enemies tasks
    	// (not entirely clear how to split this algorithmically)
    	// do shortest distance for now -- need to fix
//    	for (Integer t: remTaskIndices) {
//    		Log.log(t + ": " + Arrays.toString(distances[t]));
//    	}
    	
    	for (Integer i : remainingShipIndices) {
    		double minDist = Double.MAX_VALUE;
    		int minTIdx = -1;
    		for (Integer t : remTaskIndices) {
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
    }
    
    private ArrayList<Integer> remainingTaskIndices() {
    	ArrayList<Integer> rti = new ArrayList<>();
    	for (int i = 0; i < taskList.size(); i++) {
    		Planet p = taskList.get(i).planet;
    		Log.log("task " + i + ", " + taskList.get(i).taskType);
    		if (taskList.get(i).taskType == TaskType.KILL_DOCKED_ENEMIES) {
    			rti.add(i);
    		} else if (taskList.get(i).taskType == TaskType.TRAVEL_DOCK_PLANET && 
    				(!p.isOwned() || p.getOwner() == gameMap.getMyPlayerId())) {
    			rti.add(i);
    		}
    	}
    	return rti;
    }
    
    private void makeMoves() {
    	for (Entry<Integer, ArrayList<Ship>> e : taskAssignments.entrySet()) {
    		Task t = taskList.get(e.getKey());
    		String shipStr = "";
    		for (Ship s : e.getValue()) shipStr += s.getId() + ", ";
    		Log.log(t + ", ships " + shipStr);
    		for (Ship ship : e.getValue()) {	
    			Planet p = t.planet;
    			Ship targetShip = t.targetShip;
    			switch (t.taskType) {
    			case DOCK_PLANET: 
    				moveList.add(new DockMove(ship, p)); break;
    			case KILL_DOCKED_ENEMIES:
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
}

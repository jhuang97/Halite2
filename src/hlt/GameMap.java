package hlt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import hlt.Ship.DockingStatus;

public class GameMap {
    private final int width, height;
    private final int playerId;
    private final List<Player> players;
    private final List<Player> playersUnmodifiable;
    private final Map<Integer, Planet> planets;
    private final List<Ship> allShips;
    private final List<Ship> allShipsUnmodifiable;

    // used only during parsing to reduce memory allocations
    private final List<Ship> currentShips = new ArrayList<>();

    public GameMap(final int width, final int height, final int playerId) {
        this.width = width;
        this.height = height;
        this.playerId = playerId;
        players = new ArrayList<>(Constants.MAX_PLAYERS);
        playersUnmodifiable = Collections.unmodifiableList(players);
        planets = new TreeMap<>();
        allShips = new ArrayList<>();
        allShipsUnmodifiable = Collections.unmodifiableList(allShips);
    }

    public int getHeight() {
        return height;
    }

    public int getWidth() {
        return width;
    }

    public int getMyPlayerId() {
        return playerId;
    }

    public List<Player> getAllPlayers() {
        return playersUnmodifiable;
    }

    public Player getMyPlayer() {
        return getAllPlayers().get(getMyPlayerId());
    }

    public Ship getShip(final int playerId, final int entityId) throws IndexOutOfBoundsException {
        return players.get(playerId).getShip(entityId);
    }

    public Planet getPlanet(final int entityId) {
        return planets.get(entityId);
    }

    public Map<Integer, Planet> getAllPlanets() {
        return planets;
    }

    public List<Ship> getAllShips() {
        return allShipsUnmodifiable;
    }

    public ArrayList<Entity> objectsBetween(Position start, Position target) {
        final ArrayList<Entity> entitiesFound = new ArrayList<>();

        addEntitiesBetween(entitiesFound, start, target, planets.values());
        addEntitiesBetween(entitiesFound, start, target, allShips);

        return entitiesFound;
    }
    
    public static ArrayList<Entity> objectsBetween(Position start, Position target, 
    		Collection<? extends Entity> entitiesToCheck) {
        final ArrayList<Entity> entitiesFound = new ArrayList<>();

        addEntitiesBetween(entitiesFound, start, target, entitiesToCheck);

        return entitiesFound;
    }

    private static void addEntitiesBetween(final List<Entity> entitiesFound,
                                           final Position start, final Position target,
                                           final Collection<? extends Entity> entitiesToCheck) {

        for (final Entity entity : entitiesToCheck) {
            if (entity.equals(start) || entity.equals(target)) {
                continue;
            }
            if (Collision.segmentCircleIntersect(start, target, entity, Constants.FORECAST_FUDGE_FACTOR)) {
                entitiesFound.add(entity);
            }
        }
    }

    public Map<Double, Entity> nearbyEntitiesByDistance(final Entity entity) {
        final Map<Double, Entity> entityByDistance = new TreeMap<>();

        for (final Planet planet : planets.values()) {
            if (planet.equals(entity)) {
                continue;
            }
            entityByDistance.put(entity.getDistanceTo(planet), planet);
        }

        for (final Ship ship : allShips) {
            if (ship.equals(entity)) {
                continue;
            }
            entityByDistance.put(entity.getDistanceTo(ship), ship);
        }

        return entityByDistance;
    }
    
    public ArrayList<Entity> nearbyEntitiesWithinDistance(
    		final ArrayList<? extends Entity> entities, double distance) {
    	ArrayList<Entity> nearby = new ArrayList<Entity>();
    	
    	planetLoop:
    	for (final Planet planet : planets.values()) {
    		for (Entity e : entities) if (planet.equals(e)) continue planetLoop;
    		for (Entity e : entities) {
    			if (e.getDistanceTo(planet) - planet.getRadius() < distance) {
    				nearby.add(planet);
    				break;
    			}
    		}
    	}
    	
    	shipLoop:
    	for (final Ship ship : allShips) {
    		for (Entity e : entities) if (ship.equals(e)) continue shipLoop;
    		for (Entity e : entities) {
    			if (e.getDistanceTo(ship) - Constants.SHIP_RADIUS < distance) {
    				nearby.add(ship);
    				break;
    			}
    		}
    	}
    	return nearby;
    }
    
    public Map<Double, Ship> hostilesNearPlanet(final Planet planet) {
    	final Map<Double, Ship> hostilesByDistance = new HashMap<>();
    	
    	for (final Ship ship : allShips) {
    		if (ship.getOwner() != getMyPlayerId()) {
    			double dist = planet.getDistanceTo(ship);
    			if (dist <= Constants.SHIP_RADIUS + Constants.DOCK_RADIUS + Constants.WEAPON_RADIUS + planet.getRadius()) {
    				hostilesByDistance.put(dist, ship);
    			}
    		}
    	}
    	return hostilesByDistance;
    }
    
    public Map<Double, Ship> hostilesNearPlanet(final Planet planet, double dist_from_planet) {
    	final Map<Double, Ship> hostilesByDistance = new HashMap<>();
    	
    	// mode 0: hostiles actually close to a planet
    	// mode 1: hostiles that can reach a planet before we finish docking
    	double radius = planet.getRadius() + dist_from_planet;
    	
    	for (final Ship ship : allShips) {
    		if (ship.getOwner() != getMyPlayerId()) {
    			double dist = planet.getDistanceTo(ship);
    			if (dist <= radius) {
    				hostilesByDistance.put(dist, ship);
    			}
    		}
    	}
    	return hostilesByDistance;
    }
    
    public Map<Double, Ship> undockedHostilesNearPlanet(final Planet planet) {
    	final Map<Double, Ship> hostilesByDistance = new HashMap<>();
    	
    	for (final Ship ship : allShips) {
    		if (ship.getOwner() != getMyPlayerId() &&
    				ship.getDockingStatus() == DockingStatus.Undocked) {
    			double dist = planet.getDistanceTo(ship);
    			if (dist <= Constants.SHIP_RADIUS + Constants.DOCK_RADIUS + Constants.WEAPON_RADIUS + planet.getRadius()) {
    				hostilesByDistance.put(dist, ship);
    			}
    		}
    	}
    	return hostilesByDistance;
    }
    
    public Map<Double, Ship> undockedFriendliesNearPlanet(final Planet planet) {
    	final Map<Double, Ship> friendliesByDistance = new HashMap<>();
    	
    	for (final Ship ship : allShips) {
    		if (ship.getOwner() == getMyPlayerId() &&
    				ship.getDockingStatus() == DockingStatus.Undocked) {
    			double dist = planet.getDistanceTo(ship);
    			if (dist <= Constants.SHIP_RADIUS + Constants.DOCK_RADIUS + Constants.WEAPON_RADIUS + planet.getRadius()) {
    				friendliesByDistance.put(dist, ship);
    			}
    		}
    	}
    	return friendliesByDistance;
    }
    
    public Map<Double, Ship> undockedFriendliesNearPlanet(final Planet planet, double dist_from_planet) {
    	final Map<Double, Ship> friendliesByDistance = new HashMap<>();
    	
    	for (final Ship ship : allShips) {
    		if (ship.getOwner() == getMyPlayerId() &&
    				ship.getDockingStatus() == DockingStatus.Undocked) {
    			double dist = planet.getDistanceTo(ship);
    			if (dist <= dist_from_planet + planet.getRadius()) {
    				friendliesByDistance.put(dist, ship);
    			}
    		}
    	}
    	return friendliesByDistance;
    }

    public GameMap updateMap(final Metadata mapMetadata) {
        final int numberOfPlayers = MetadataParser.parsePlayerNum(mapMetadata);

        players.clear();
        planets.clear();
        allShips.clear();

        // update players info
        for (int i = 0; i < numberOfPlayers; ++i) {
            currentShips.clear();
            final Map<Integer, Ship> currentPlayerShips = new TreeMap<>();
            final int playerId = MetadataParser.parsePlayerId(mapMetadata);

            final Player currentPlayer = new Player(playerId, currentPlayerShips);
            MetadataParser.populateShipList(currentShips, playerId, mapMetadata);
            allShips.addAll(currentShips);

            for (final Ship ship : currentShips) {
                currentPlayerShips.put(ship.getId(), ship);
            }
            players.add(currentPlayer);
        }

        final int numberOfPlanets = Integer.parseInt(mapMetadata.pop());

        for (int i = 0; i < numberOfPlanets; ++i) {
            final List<Integer> dockedShips = new ArrayList<>();
            final Planet planet = MetadataParser.newPlanetFromMetadata(dockedShips, mapMetadata);
            planets.put(planet.getId(), planet);
        }

        if (!mapMetadata.isEmpty()) {
            throw new IllegalStateException("Failed to parse data from Halite game engine. Please contact maintainers.");
        }

        return this;
    }
}

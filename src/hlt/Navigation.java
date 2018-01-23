package hlt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.TreeMap;

public class Navigation {
	
	static class IndexSort implements Comparable<IndexSort>{
		double d;
		int idx;

		public IndexSort(double d, int idx) {
			this.d = d;
			this.idx = idx;
		}

		@Override
		public int compareTo(IndexSort o) {
			return Double.compare(this.d, o.d);
		}
	}
	
	public static ArrayList<Move> reviseMovesCollision_v3(
			final GameMap gameMap,
			final ArrayList<ThrustMove> moveList,
			final ArrayList<Ship> shipList
			) {
		ArrayList<Entity> es = gameMap.nearbyEntitiesWithinDistance(shipList, 
				2*Constants.MAX_SPEED + 2*Constants.SHIP_RADIUS + 0.01);
//		for (Entity e : es) Log.log(e.toString());
		
		double vtot_x = 0;
		double vtot_y = 0;
		for (ThrustMove m : moveList) {
			vtot_x += m.getdX();
			vtot_y += m.getdY();
		}
		
		ArrayList<IndexSort> forwardness = new ArrayList<>();
		for (int i = 0; i < shipList.size(); i++) {
			double pos = shipList.get(i).getXPos() * vtot_x + shipList.get(i).getYPos() * vtot_y;
			forwardness.add(new IndexSort(pos, i));
		}
		Collections.sort(forwardness);
		Collections.reverse(forwardness);
		int[] precedence = new int[forwardness.size()];
		for (int i = 0; i < precedence.length; i++) {
			precedence[i] = forwardness.get(i).idx;
		}		
		
		ArrayList<Move> revisedMoves = new ArrayList<>();
		for (int i = 0; i < shipList.size(); i++)
			revisedMoves.add(null);
		revisedMoves.set(precedence[0], moveList.get(precedence[0])); // keep most forward ship's move
		for (int i = 1; i < precedence.length; i++) {
			// add final position of previous ship to the list of entities to avoid
			Ship prevShip = shipList.get(precedence[i-1]);
			ThrustMove prevMove = (ThrustMove) revisedMoves.get(precedence[i-1]);
			double xfinal = prevShip.getXPos() + prevMove.getdX();
			double yfinal = prevShip.getYPos() + prevMove.getdY();
			es.add(new Entity(-1, -i, xfinal, yfinal, -1, Constants.SHIP_RADIUS*1.2));
			
			// calculate move for current ship
			Ship currShip = null;
			try {
				currShip = shipList.get(precedence[i]);
			} catch (Exception e) {
				System.err.println("i: " + i + ", precedence: " + Arrays.toString(precedence) + ", shipList = " + shipList);
			}
			ThrustMove badMove = moveList.get(precedence[i]);
			xfinal = currShip.getXPos() + badMove.getdX();
			yfinal = currShip.getYPos() + badMove.getdY();
			revisedMoves.set(precedence[i], navShipAsRevision(es, currShip, new Position(xfinal, yfinal),
					Constants.MAX_SPEED, Constants.MAX_NAVIGATION_CORRECTIONS, Math.PI/180.0));
			
			Log.log("revised move of ship " + currShip.getId());
		}
		
		// double-check for collisions
		boolean needSecondRevision = false;
		collisionLoop2:
        for (int i = 0; i < moveList.size(); i++) {
        	for (int j = i+1; j < moveList.size(); j++) {
        		if (Collision.twoShipCollide(shipList.get(i), (ThrustMove) revisedMoves.get(i),
        				shipList.get(j), (ThrustMove) revisedMoves.get(j))) {
        			Log.log("need second revision of ship moves");
        			needSecondRevision = true;
        			break collisionLoop2;
        		}
        	}
        }
		
		// second revision
		if (needSecondRevision) {
			for (int i = 1; i < precedence.length; i++) {
				// add final position of previous ship to the list of entities to avoid
				Ship prevShip = shipList.get(precedence[i-1]);
				ThrustMove prevMove = (ThrustMove) revisedMoves.get(precedence[i-1]);
				double xi = prevShip.getXPos();
				double yi = prevShip.getYPos();
				double xf = prevShip.getXPos() + prevMove.getdX();
				double yf = prevShip.getYPos() + prevMove.getdY();
				double numBetween = 4*prevMove.getThrust();
				double rr;
				for (double r = 0; r <= numBetween; r++) {
					rr = r/numBetween; 
					es.add(new Entity(-1, -i, rr*xi + (1-rr)*xf, rr*yi + (1-rr)*yf, 
							-1, Constants.SHIP_RADIUS*1.2));
				}
				
				// calculate move for current ship
				Ship currShip = shipList.get(precedence[i]);
				ThrustMove badMove = (ThrustMove) revisedMoves.get(precedence[i]);
				double xfinal = currShip.getXPos() + badMove.getdX();
				double yfinal = currShip.getYPos() + badMove.getdY();
				revisedMoves.set(precedence[i], navShipAsRevision(es, currShip, new Position(xfinal, yfinal),
						Constants.MAX_SPEED, Constants.MAX_NAVIGATION_CORRECTIONS, Math.PI/180.0));
				
				Log.log("revised move of ship " + currShip.getId());
			}
		}
		
		return revisedMoves;
	}

	public static ArrayList<Move> reviseMovesCollision_v2(
			final GameMap gameMap,
			final ArrayList<ThrustMove> moveList,
			final ArrayList<Ship> shipList
			) {
		ArrayList<Entity> es = gameMap.nearbyEntitiesWithinDistance(shipList, 
				2*Constants.MAX_SPEED + 2*Constants.SHIP_RADIUS + 0.01);
//		for (Entity e : es) Log.log(e.toString());
		
		double vtot_x = 0;
		double vtot_y = 0;
		for (ThrustMove m : moveList) {
			vtot_x += m.getdX();
			vtot_y += m.getdY();
		}
		TreeMap<Double, Integer> forwardness = new TreeMap<>(Collections.reverseOrder());
		for (int i = 0; i < shipList.size(); i++) {
			double pos = shipList.get(i).getXPos() * vtot_x + shipList.get(i).getYPos() * vtot_y;
			forwardness.put(pos, i);
		}
		Integer[] precedence = forwardness.values().toArray(new Integer[shipList.size()]);
		ArrayList<Move> revisedMoves = new ArrayList<>();
		for (int i = 0; i < shipList.size(); i++)
			revisedMoves.add(null);
		revisedMoves.set(precedence[0], moveList.get(precedence[0])); // keep most forward ship's move
		for (int i = 1; i < precedence.length; i++) {
			// add final position of previous ship to the list of entities to avoid
			Ship prevShip = shipList.get(precedence[i-1]);
			ThrustMove prevMove = (ThrustMove) revisedMoves.get(precedence[i-1]);
			double xfinal = prevShip.getXPos() + prevMove.getdX();
			double yfinal = prevShip.getYPos() + prevMove.getdY();
			es.add(new Entity(-1, -i, xfinal, yfinal, -1, Constants.SHIP_RADIUS*1.2));
			
			// calculate move for current ship
			Ship currShip = null;
			try {
				currShip = shipList.get(precedence[i]);
			} catch (Exception e) {
				System.err.println("i: " + i + ", precedence: " + Arrays.toString(precedence) + ", shipList = " + shipList);
			}
			ThrustMove badMove = moveList.get(precedence[i]);
			xfinal = currShip.getXPos() + badMove.getdX();
			yfinal = currShip.getYPos() + badMove.getdY();
			revisedMoves.set(precedence[i], navShipAsRevision(es, currShip, new Position(xfinal, yfinal),
					Constants.MAX_SPEED, Constants.MAX_NAVIGATION_CORRECTIONS, Math.PI/180.0));
			
			Log.log("revised move of ship " + currShip.getId());
		}
		
		// double-check for collisions
		boolean needSecondRevision = false;
		collisionLoop2:
        for (int i = 0; i < moveList.size(); i++) {
        	for (int j = i+1; j < moveList.size(); j++) {
        		if (Collision.twoShipCollide(shipList.get(i), (ThrustMove) revisedMoves.get(i),
        				shipList.get(j), (ThrustMove) revisedMoves.get(j))) {
        			Log.log("need second revision of ship moves");
        			needSecondRevision = true;
        			break collisionLoop2;
        		}
        	}
        }
		
		// second revision
		if (needSecondRevision) {
			for (int i = 1; i < precedence.length; i++) {
				// add final position of previous ship to the list of entities to avoid
				Ship prevShip = shipList.get(precedence[i-1]);
				ThrustMove prevMove = (ThrustMove) revisedMoves.get(precedence[i-1]);
				double xi = prevShip.getXPos();
				double yi = prevShip.getYPos();
				double xf = prevShip.getXPos() + prevMove.getdX();
				double yf = prevShip.getYPos() + prevMove.getdY();
				double numBetween = 4*prevMove.getThrust();
				double rr;
				for (double r = 0; r <= numBetween; r++) {
					rr = r/numBetween; 
					es.add(new Entity(-1, -i, rr*xi + (1-rr)*xf, rr*yi + (1-rr)*yf, 
							-1, Constants.SHIP_RADIUS*1.2));
				}
				
				// calculate move for current ship
				Ship currShip = shipList.get(precedence[i]);
				ThrustMove badMove = (ThrustMove) revisedMoves.get(precedence[i]);
				double xfinal = currShip.getXPos() + badMove.getdX();
				double yfinal = currShip.getYPos() + badMove.getdY();
				revisedMoves.set(precedence[i], navShipAsRevision(es, currShip, new Position(xfinal, yfinal),
						Constants.MAX_SPEED, Constants.MAX_NAVIGATION_CORRECTIONS, Math.PI/180.0));
				
				Log.log("revised move of ship " + currShip.getId());
			}
		}
		
		return revisedMoves;
	}
	
	public static ArrayList<Move> reviseMovesCollision(
			final GameMap gameMap,
			final ArrayList<ThrustMove> moveList,
			final ArrayList<Ship> shipList
			) {
		ArrayList<Entity> es = gameMap.nearbyEntitiesWithinDistance(shipList, 2.5*Constants.MAX_SPEED);
//		for (Entity e : es) Log.log(e.toString());
		
		double vtot_x = 0;
		double vtot_y = 0;
		for (ThrustMove m : moveList) {
			vtot_x += m.getdX();
			vtot_y += m.getdY();
		}
		TreeMap<Double, Integer> forwardness = new TreeMap<>(Collections.reverseOrder());
		for (int i = 0; i < shipList.size(); i++) {
			double pos = shipList.get(i).getXPos() * vtot_x + shipList.get(i).getYPos() * vtot_y;
			forwardness.put(pos, i);
		}
		Integer[] precedence = forwardness.values().toArray(new Integer[shipList.size()]);
		ArrayList<Move> revisedMoves = new ArrayList<>();
		for (int i = 0; i < shipList.size(); i++)
			revisedMoves.add(null);
		revisedMoves.set(precedence[0], moveList.get(precedence[0]));
		for (int i = 1; i < precedence.length; i++) {
			// add final position of previous ship to the list of entities to avoid
			Ship prevShip = shipList.get(precedence[i-1]);
			ThrustMove prevMove = (ThrustMove) revisedMoves.get(precedence[i-1]);
			double xfinal = prevShip.getXPos() + prevMove.getdX();
			double yfinal = prevShip.getYPos() + prevMove.getdY();
			es.add(new Entity(-1, -i, xfinal, yfinal, -1, Constants.SHIP_RADIUS*1.2));
			
			// calculate move for current ship
			Ship currShip = shipList.get(precedence[i]);
			ThrustMove badMove = moveList.get(precedence[i]);
			xfinal = currShip.getXPos() + badMove.getdX();
			yfinal = currShip.getYPos() + badMove.getdY();
			revisedMoves.set(precedence[i], navShipAsRevision(es, currShip, new Position(xfinal, yfinal),
					Constants.MAX_SPEED, Constants.MAX_NAVIGATION_CORRECTIONS, Math.PI/180.0));
			
			Log.log("revised move of ship " + currShip.getId());
		}
		
		return revisedMoves;
	}
	
	public static ThrustMove navShipAsRevision(
            ArrayList<Entity> nearbyEntities,
            final Ship ship,
            final Position targetPos,
            final int maxThrust,
            final int maxCorrections,
            final double angularStepRad)
    {
    	
        if (maxCorrections <= 0) {
            return null;
        }
        
        Position targetPos1 = new Position(targetPos.getXPos(), targetPos.getYPos());
        Position targetPos2 = new Position(targetPos.getXPos(), targetPos.getYPos());

        double distance1 = ship.getDistanceTo(targetPos);
        double angleRad1 = ship.orientTowardsInRad(targetPos);
        double distance2 = ship.getDistanceTo(targetPos);
        double angleRad2 = ship.orientTowardsInRad(targetPos);
        double distance = 0;
        double angleRad = 0;
        
    	int i=0;
        for (; i < maxCorrections; i++) {
            if (GameMap.objectsBetween(ship, targetPos1, nearbyEntities).isEmpty()) {
            	distance = distance1;
            	angleRad = angleRad1;
            	break;
            } else if (GameMap.objectsBetween(ship, targetPos2, nearbyEntities).isEmpty()) {
            	distance = distance2;
            	angleRad = angleRad2;
            	break;
            } else {
                final double newTargetDx1 = Math.cos(angleRad1 + angularStepRad) * distance1;
                final double newTargetDy1 = Math.sin(angleRad1 + angularStepRad) * distance1;
                final Position newTarget1 = new Position(ship.getXPos() + newTargetDx1, ship.getYPos() + newTargetDy1);
                targetPos1 = newTarget1;
                distance1 = ship.getDistanceTo(targetPos1);
                angleRad1 = ship.orientTowardsInRad(targetPos1);
                
                final double newTargetDx2 = Math.cos(angleRad2 - angularStepRad) * distance2;
                final double newTargetDy2 = Math.sin(angleRad2 - angularStepRad) * distance2;
                final Position newTarget2 = new Position(ship.getXPos() + newTargetDx2, ship.getYPos() + newTargetDy2);
                targetPos2 = newTarget2;
                distance2 = ship.getDistanceTo(targetPos2);
                angleRad2 = ship.orientTowardsInRad(targetPos2);
            }
	        if (i >= maxCorrections) {
	        	Log.log("couldn't find good revision; sending zero thrust move");
	        	return new ThrustMove(ship, 0, 0);
	        }
        }

        final int thrust;
        if (distance < maxThrust) {
            // Do not round up, since overshooting might cause collision.
            thrust = (int) distance;
        }
        else {
            thrust = maxThrust;
        }

        final int angleDeg = Util.angleRadToDegClipped(angleRad);

        return new ThrustMove(ship, angleDeg, thrust);
    }
	
    public static ThrustMove navShipToDock_v2(
            final GameMap gameMap,
            final Ship ship,
            final Entity dockTarget,
            final int maxThrust)
    {
        final int maxCorrections = Constants.MAX_NAVIGATION_CORRECTIONS;
        final boolean avoidObstacles = true;
        final double angularStepRad = Math.PI/180.0;
        final Position targetPos = ship.getClosestPoint(dockTarget);

        return navShipTowardsTarget_v2(gameMap, ship, targetPos, maxThrust, avoidObstacles, maxCorrections, angularStepRad);
    }
    
    public static ThrustMove navShipToHostileShip_v2(
            final GameMap gameMap,
            final Ship ship,
            final Ship hostileShip,
            final int maxThrust)
    {
        final int maxCorrections = Constants.MAX_NAVIGATION_CORRECTIONS;
        final boolean avoidObstacles = true;
        final double angularStepRad = Math.PI/180.0;
        final Position targetPos = ship.getClosestPoint(hostileShip);

        return navShipTowardsTarget_v2(gameMap, ship, targetPos, maxThrust, avoidObstacles, maxCorrections, angularStepRad);
    }
	
    public static ThrustMove navShipTowardsTarget_v2(
            final GameMap gameMap,
            final Ship ship,
            final Position targetPos,
            final int maxThrust,
            final boolean avoidObstacles,
            final int maxCorrections,
            final double angularStepRad)
    {
    	
        if (maxCorrections <= 0) {
            return null;
        }
        
        Position targetPos1 = new Position(targetPos.getXPos(), targetPos.getYPos());
        Position targetPos2 = new Position(targetPos.getXPos(), targetPos.getYPos());

        double distance1 = ship.getDistanceTo(targetPos);
        double angleRad1 = ship.orientTowardsInRad(targetPos);
        double distance2 = ship.getDistanceTo(targetPos);
        double angleRad2 = ship.orientTowardsInRad(targetPos);
        double distance = 0;
        double angleRad = 0;
        
        if (avoidObstacles) {
        	int i=0;
	        for (; i < maxCorrections; i++) {
	            if (gameMap.objectsBetween(ship, targetPos1).isEmpty()) {
	            	distance = distance1;
	            	angleRad = angleRad1;
	            	break;
	            } else if (gameMap.objectsBetween(ship, targetPos2).isEmpty()) {
	            	distance = distance2;
	            	angleRad = angleRad2;
	            	break;
	            } else {
	                final double newTargetDx1 = Math.cos(angleRad1 + angularStepRad) * distance1;
	                final double newTargetDy1 = Math.sin(angleRad1 + angularStepRad) * distance1;
	                final Position newTarget1 = new Position(ship.getXPos() + newTargetDx1, ship.getYPos() + newTargetDy1);
	                targetPos1 = newTarget1;
	                distance1 = ship.getDistanceTo(targetPos1);
	                angleRad1 = ship.orientTowardsInRad(targetPos1);
	                
	                final double newTargetDx2 = Math.cos(angleRad2 - angularStepRad) * distance2;
	                final double newTargetDy2 = Math.sin(angleRad2 - angularStepRad) * distance2;
	                final Position newTarget2 = new Position(ship.getXPos() + newTargetDx2, ship.getYPos() + newTargetDy2);
	                targetPos2 = newTarget2;
	                distance2 = ship.getDistanceTo(targetPos2);
	                angleRad2 = ship.orientTowardsInRad(targetPos2);
	            }
	        }
	        if (i >= maxCorrections) return null;
        }

        final int thrust;
        if (distance < maxThrust) {
            // Do not round up, since overshooting might cause collision.
            thrust = (int) distance;
        }
        else {
            thrust = maxThrust;
        }

        final int angleDeg = Util.angleRadToDegClipped(angleRad);

        return new ThrustMove(ship, angleDeg, thrust);
    }

    public static ThrustMove navigateShipToDock(
            final GameMap gameMap,
            final Ship ship,
            final Entity dockTarget,
            final int maxThrust)
    {
        final int maxCorrections = Constants.MAX_NAVIGATION_CORRECTIONS;
        final boolean avoidObstacles = true;
        final double angularStepRad = Math.PI/180.0;
        final Position targetPos = ship.getClosestPoint(dockTarget);

        return navigateShipTowardsTarget(gameMap, ship, targetPos, maxThrust, avoidObstacles, maxCorrections, angularStepRad);
    }
    
    public static ThrustMove navigateShipToHostileShip(
            final GameMap gameMap,
            final Ship ship,
            final Ship hostileShip,
            final int maxThrust)
    {
        final int maxCorrections = Constants.MAX_NAVIGATION_CORRECTIONS;
        final boolean avoidObstacles = true;
        final double angularStepRad = Math.PI/180.0;
        final Position targetPos = ship.getClosestPoint(hostileShip);

        return navigateShipTowardsTarget(gameMap, ship, targetPos, maxThrust, avoidObstacles, maxCorrections, angularStepRad);
    }
    
    /**
     * for micro-ing around a planet
     * @param gameMap
     * @param ship
     * @param hostileShip
     * @param maxThrust
     * @return
     */
    public static ThrustMove navigateShipToHostileShipNearPlanet(
            final GameMap gameMap,
            final Ship ship,
            final Ship hostileShip,
            final Planet planet,
            final int maxThrust)
    {
        final int maxCorrections = Constants.MAX_NAVIGATION_CORRECTIONS;
        final boolean avoidObstacles = true;
        
        double angularStepRad = Math.PI/180.0;
        // positive makes it go clockwise around planet;
        // negative makes it go counterclockwise around planet
        double xs = ship.getXPos();        double ys = ship.getYPos();
        double xh = hostileShip.getXPos(); double yh = hostileShip.getYPos();
        double xp = planet.getXPos();      double yp = planet.getYPos();
        if ((yp-ys)*xh - (xp-xs)*yh + xp*ys - yp*xs >= 0) angularStepRad *= -1;
        
        final Position targetPos = ship.getClosestPoint(hostileShip);

        return navigateShipTowardsTarget(gameMap, ship, targetPos, maxThrust, avoidObstacles, maxCorrections, angularStepRad);
    }

    public static ThrustMove navigateShipTowardsTarget(
            final GameMap gameMap,
            final Ship ship,
            final Position targetPos,
            final int maxThrust,
            final boolean avoidObstacles,
            final int maxCorrections,
            final double angularStepRad)
    {
        if (maxCorrections <= 0) {
            return null;
        }

        final double distance = ship.getDistanceTo(targetPos);
        final double angleRad = ship.orientTowardsInRad(targetPos);

        if (avoidObstacles && !gameMap.objectsBetween(ship, targetPos).isEmpty()) {
            final double newTargetDx = Math.cos(angleRad + angularStepRad) * distance;
            final double newTargetDy = Math.sin(angleRad + angularStepRad) * distance;
            final Position newTarget = new Position(ship.getXPos() + newTargetDx, ship.getYPos() + newTargetDy);

            return navigateShipTowardsTarget(gameMap, ship, newTarget, maxThrust, true, (maxCorrections-1), angularStepRad);
        }

        final int thrust;
        if (distance < maxThrust) {
            // Do not round up, since overshooting might cause collision.
            thrust = (int) distance;
        }
        else {
            thrust = maxThrust;
        }

        final int angleDeg = Util.angleRadToDegClipped(angleRad);

        return new ThrustMove(ship, angleDeg, thrust);
    }
}

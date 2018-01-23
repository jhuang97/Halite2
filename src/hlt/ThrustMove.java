package hlt;

public class ThrustMove extends Move {

    private final int angleDeg;
    private final int thrust;

    public ThrustMove(final Ship ship, final int angleDeg, final int thrust) {
        super(MoveType.Thrust, ship);
        this.thrust = thrust;
        this.angleDeg = angleDeg;
    }

    public int getAngle() {
        return angleDeg;
    }

    public int getThrust() {
        return thrust;
    }
    
    public double getdX() {
    	return thrust * Math.cos(angleDeg * Math.PI/180);
    }
    
    public double getdY() {
    	return thrust * Math.sin(angleDeg * Math.PI/180);
    }

	@Override
	public String toString() {
		return "ThrustMove [angleDeg=" + angleDeg + ", thrust=" + thrust + ", ship " + getShip().getId() + "]";
	}
    
}

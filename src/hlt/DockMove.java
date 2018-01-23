package hlt;

public class DockMove extends Move {

    @Override
	public String toString() {
		return "DockMove [planet " + destinationId + ", ship " + getShip().getId() + "]";
	}

	private final long destinationId;

    public DockMove(final Ship ship, final Planet planet) {
        super(MoveType.Dock, ship);
        destinationId = planet.getId();
    }

    public long getDestinationId() {
        return destinationId;
    }
}

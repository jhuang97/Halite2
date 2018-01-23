# Halite2
A bot for Halite II written in Java.

## Development
I used the Java starter pack provided by Two Sigma.

First, starting with the bot in the starter pack, I developed several versions of what I called QueueBot.  Then, I rewrote my bot from scratch, calling it MatrixBot.  MyBot.java represents the latest version (the version that is in the finals); I call it MatrixBot9.

## Design
With MatrixBot, I was hoping to be able to intelligently assign appropriate amounts of ships to various planets, instead of having each ship decide what it wanted to do.  This goal was not entirely realized - while I do not send too many ships to dock at planets, I never really figured out how to globally assign the correct amount of ships to defend planets or attack planets.  Instead, roughly speaking, I just have leftover ships go to the nearest planet that requires ships to defend or attack.

For me, avoiding collisions and running away when outnumbered were both afterthoughts.  I implemented those two things as post-processing steps: after I generate the list of moves (ThrustMoves, DockMoves, etc.), I call two methods to revise this list of moves to avoid self-collisions and run away.  Towards the end of the season, it seemed necessary to make collision handling and retreating part of the main body of the code, but I never got around to that.

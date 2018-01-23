import json, random, subprocess

processes = [
	"insert_bot_1_here",
	"insert_bot_2_here",
]

scores = [0,0]

positions = [0,1]

print("{} --- {}".format(processes[0], processes[1]))

while 1:

	random.shuffle(positions)

	output = subprocess.check_output(
		"halite.exe -d \"360 240\" --no-compression -q \"{}\" \"{}\"".format(processes[positions[0]], processes[positions[1]])
		).decode("ascii")

	result = json.loads(output)

	for key in result["stats"]:
		rank = result["stats"][key]["rank"]
		i = positions[int(key)]

		if rank == 1:
			scores[i] += 1

	print(scores)


execute unless entity @a run schedule function leaper_terrain:start 2s replace
execute if entity @a unless score built leaper_lab matches 1 run function leaper_terrain:build
execute if entity @a run gamemode creative @a
execute if entity @a run tp @a 0 -58 16 180 0
execute as @a at @s run mm leaper spawn
execute as @a run mm leaper pace horrific
execute as @a run mm leaper terrain auto
execute as @a run mm leaper stick
execute if entity @a run tp @a 10 -53 -13 38 20
execute as @a run mm leaper unwatch
tellraw @a {"text":"Leaper Terrain Lab — right-click a supported surface; Shift-right-click to stop.","color":"gold"}
tellraw @a [{"text": "[Careful] ", "color": "aqua", "clickEvent": {"action": "run_command", "value": "/mm leaper pace careful"}}, {"text": "[Standard] ", "color": "aqua", "clickEvent": {"action": "run_command", "value": "/mm leaper pace standard"}}, {"text": "[Horrific] ", "color": "gold", "clickEvent": {"action": "run_command", "value": "/mm leaper pace horrific"}}, {"text": "[Hilltop] ", "color": "green", "clickEvent": {"action": "run_command", "value": "/mm leaper goto 0.5 -50.5"}}, {"text": "[Across the hill] ", "color": "green", "clickEvent": {"action": "run_command", "value": "/mm leaper goto 0.5 -98.5"}}, {"text": "[Follow] ", "color": "aqua", "clickEvent": {"action": "run_command", "value": "/mm leaper watch"}}, {"text": "[Free camera] ", "color": "aqua", "clickEvent": {"action": "run_command", "value": "/mm leaper unwatch"}}, {"text": "[Stop] ", "color": "red", "clickEvent": {"action": "run_command", "value": "/mm leaper stop"}}]

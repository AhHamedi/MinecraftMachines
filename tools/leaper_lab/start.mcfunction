execute unless entity @a run schedule function leaper_lab:start 2s replace
execute if entity @a run gamemode creative @a
execute if entity @a run tp @a 0 -58 16 180 0
execute as @a at @s run mm leaper spawn
execute as @a run mm leaper pace fast
execute as @a run mm leaper stick
execute if entity @a run tp @a 10 -53 -13 38 20
execute as @a run mm leaper unwatch
tellraw @a {"text": "Leaper navigation: right-click clear ground with the Leaper stick. Shift-right-click to stop.", "color": "gold"}
tellraw @a [{"text": "[Standard pace] ", "color": "aqua", "clickEvent": {"action": "run_command", "value": "/mm leaper pace standard"}}, {"text": "[Horrific sprint] ", "color": "gold", "clickEvent": {"action": "run_command", "value": "/mm leaper pace horrific"}}, {"text": "[Stick] ", "color": "aqua", "clickEvent": {"action": "run_command", "value": "/mm leaper stick"}}, {"text": "[Stop] ", "color": "red", "clickEvent": {"action": "run_command", "value": "/mm leaper stop"}}, {"text": "[Free camera] ", "color": "aqua", "clickEvent": {"action": "run_command", "value": "/mm leaper unwatch"}}, {"text": "[Follow camera] ", "color": "aqua", "clickEvent": {"action": "run_command", "value": "/mm leaper watch"}}, {"text": "[Status] ", "color": "aqua", "clickEvent": {"action": "run_command", "value": "/mm leaper status"}}, {"text": "[Articulate] ", "color": "aqua", "clickEvent": {"action": "run_command", "value": "/mm leaper articulate"}}]

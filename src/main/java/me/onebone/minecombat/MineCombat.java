package me.onebone.minecombat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.level.Position;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import cn.nukkit.utils.TextFormat;
import cn.nukkit.utils.Utils;
import me.onebone.minecombat.game.Game;

public class MineCombat extends PluginBase implements Listener{
	public static final int MODE_STANDBY = 0;
	public static final int MODE_ONGOING = 1;

	private Map<String, String> lang;

	
	private HashMap<Integer, GameContainer> ongoing = new HashMap<>();
	private HashMap<String, Class<? extends Game>> games = new HashMap<>();
	private HashMap<String, Participant> players = new HashMap<>();

	private int index = 0;

	private boolean joinRandom = true;

	/**
	 * Make player to join the game
	 *
	 * @param game
	 * @param player
	 * @return `true` if success, `false` if not
	 */
	public boolean joinGame(Game game, Participant player){
		if(player.getJoinedGame() != null){
			return false;
		}

		return player.joinGame(game);
	}

	/**
	 * Makes player to leave the game
	 *
	 * @param game
	 * @param player
	 * @return `true` if success, `false` if not
	 */
	public boolean leaveGame(Participant player){
		return player.leaveGame();
	}

	public boolean initGame(String name, Position[] position, String tag, final List<Participant> players){
		if(!games.containsKey(name)){
			return false;
		}
		
		if(position.length < 2 || position[0] == null || position[1] ==null){
			position = null;
		}
		try{
			Game game = (Game) games.get(name).getConstructor(MineCombat.class, String.class, Position[].class).newInstance(this, tag, position);
			final GameContainer container = new GameContainer(game);
	
			if(game.getStandByTime() > 0){
				if(this.standBy(container, players)){
					this.ongoing.put(index++, container);
					return true;
				}

				return false;
			}

			this.ongoing.put(index++, container);
			
			return container.startGame(players);
		}catch(Exception e){
			return false;
		}
	}
	
	public boolean stopGame(Game game){
		for(int index : this.ongoing.keySet()){
			GameContainer container = this.ongoing.get(index);

			if(container.game == game){
				if(container.taskId != -1 && this.getServer().getScheduler().isQueued(container.taskId)){
					this.getServer().getScheduler().cancelTask(container.taskId);
				}
				this.ongoing.remove(index);
				return true;
			}
		}
		return false;
	}

	private boolean standBy(final GameContainer container, final List<Participant> players){
		if(container.standBy(players)){
			container.taskId = this.getServer().getScheduler().scheduleDelayedTask(new PluginTask<MineCombat>(this){
				public void onRun(int currentTick){
					if(!container.startGame(players)){
						MineCombat.this.standBy(container, players);
					}
				}
			}, container.game.getStandByTime()).getTaskId();

			return true;
		}
		return false;
	}
	
	public String getMessage(String key, Object... params){
		if(this.lang.containsKey(key)){
			return replaceMessage(this.lang.get(key), params);
		}
		return "Could not find message with " + key;
	}
	
	private String replaceMessage(String lang, Object[] params){
		StringBuilder builder = new StringBuilder();
		
		for(int i = 0; i < lang.length(); i++){
			char c = lang.charAt(i);
			if(c == '{'){
				int index;
				if((index = lang.indexOf('}', i)) != -1){
					try{
						String p = lang.substring(i + 1, index);
						int param = Integer.parseInt(p);
						
						if(params.length > param){
							i = index;
							
							builder.append(params[param]);
							continue;
						}
					}catch(NumberFormatException e){}
				}
			}
			builder.append(c);
		}
		
		return TextFormat.colorize(builder.toString());
	}
	
	@Override
	public void onEnable(){
		this.saveDefaultConfig();

		this.joinRandom = this.getConfig().get("join.random", true);

		String name = this.getConfig().get("language", "eng");
		InputStream is = this.getResource("lang_" + name + ".json");
		if(is == null){
			this.getLogger().critical("Could not load language file. Changing to default.");
			
			is = this.getResource("lang_eng.json");
		}
		
		try{
			lang = new GsonBuilder().create().fromJson(Utils.readFile(is), new TypeToken<LinkedHashMap<String, String>>(){}.getType());
		}catch(JsonSyntaxException | IOException e){
			this.getLogger().critical(e.getMessage());
		}
		
		if(!name.equals("eng")){
			try{
				LinkedHashMap<String, String> temp = new GsonBuilder().create().fromJson(Utils.readFile(this.getResource("lang_eng.json")), new TypeToken<LinkedHashMap<String, String>>(){}.getType());
				temp.forEach((k, v) -> {
					if(!lang.containsKey(k)){
						lang.put(k, v);
					}
				});
			}catch(IOException e){
				this.getLogger().critical(e.getMessage());
			}
		}

		this.startGames();

		this.getServer().getPluginManager().registerEvents(this, this);
	}

	private void startGames(){
		Map<String, Map<String, Object>> list = this.getConfig().get("games", new LinkedHashMap<String, Map<String, Object>>());

		for(String key : list.keySet()){
			Map<String, Object> game = list.get(key);

			String type = (String) game.getOrDefault("type", "gunmatch").toString().toLowerCase();
			String pos1 = (String) game.getOrDefault("pos1", "null");
			String pos2 = (String) game.getOrDefault("pos2", "null");

			Position start = null, end = null;
			if(pos1 != null && end != null){
				String[] pos = pos1.split(" ");
				if(pos.length >= 4){
					try{
						Double x = Double.parseDouble(pos[0]);
						Double y = Double.parseDouble(pos[1]);
						Double z = Double.parseDouble(pos[2]);
						String world = pos[3];

						start = new Position(x, y, z, this.getServer().getLevelByName(world));
					}catch(Exception e){}
				}

				pos = pos2.split(" ");
				if(pos.length >= 4){
					try{
						Double x = Double.parseDouble(pos[0]);
						Double y = Double.parseDouble(pos[1]);
						Double z = Double.parseDouble(pos[2]);
						String world = pos[3];

						end = new Position(x, y, z, this.getServer().getLevelByName(world));
					}catch(Exception e){}
				}
			}

			if(end == null || start == null){
				end = start = null;

				this.getLogger().notice(this.getMessage("game.unlimitedWorld", key));
			}

			if(this.games.containsKey(game)){
				this.initGame(type, new Position[]{
					start, end
				}, key, new ArrayList<Participant>());
			}
		}
	}

	@Override
	public void onDisable(){
		this.ongoing.values().forEach(container -> {
			container.game.closeGame();
		});
		this.ongoing.clear();
	}
	
	@EventHandler
	public void onPlayerMove(PlayerMoveEvent event){
		Player player = event.getPlayer();
		String username = player.getName().toLowerCase();
		
		if(players.containsKey(username)){
			Participant participant = players.get(username);

			Game game;
			if((game = participant.getJoinedGame()) != null){
				game.onParticipantMove(participant);
			}
		}
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event){
		Player player = event.getPlayer();
		String username = player.getName().toLowerCase();

		Participant participant = new Participant(player);

		if(this.joinRandom){
			if(this.ongoing.size() > 0){
				Random random = new Random();
				Game game = this.ongoing.get(random.nextInt(this.ongoing.size())).game;
				
				this.joinGame(game, participant);
			}else{
				player.sendMessage(this.getMessage("join.failed"));
			}
		}
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event){
		Player player = event.getPlayer();

		String username = player.getName().toLowerCase();
		if(players.containsKey(username)){
			players.remove(username);
		}
	}
	
	public boolean addGame(Class<? extends Game> game){
		if(this.games.containsKey(game.getName().toLowerCase())){
			return false;
		}
		
		this.games.put(game.getName().toLowerCase(), game);
		return true;
	}
	
	private class GameContainer{
		private Game game = null;
		private int taskId = -1;
		
		public GameContainer(Game game){
			if(game == null){
				throw new IllegalArgumentException("Cannot add null game");
			}
			
			this.game = game;
		}
		
		
		/**
		 * Starts game with positions and players. Provide position as null apply to all worlds
		 * 
		 * @param participants
		 * @return
		 */
		public boolean startGame(List<Participant> participants){
			if(game._startGame(participants)){
				this.taskId = MineCombat.this.getServer().getScheduler().scheduleDelayedTask(new PluginTask<MineCombat>(MineCombat.this){
					@Override
					public void onRun(int currentTick){
						GameContainer.this.stopGame();
					}
				}, game.getGameTime()).getTaskId();

				return true;
			}
			return false;
		}

		public void stopGame(){
			game._stopGame();
		}
		
		/**
		 * Stand by players
		 * 
		 * @return
		 */
		public boolean standBy(List<Participant> participants){
			return game._standBy(participants);
		}
	}
}

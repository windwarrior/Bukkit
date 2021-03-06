package org.bukkit.plugin;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.Validate;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommandYamlParser;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.util.FileUtil;

import com.google.common.collect.ImmutableSet;

/**
 * Handles all plugin management from the Server
 */
public final class SimplePluginManager implements PluginManager {
    private final Server server;
    private final Map<Pattern, PluginLoader> fileAssociations = new HashMap<Pattern, PluginLoader>();
    private final List<Plugin> plugins = new ArrayList<Plugin>();
    private final Map<String, Plugin> lookupNames = new HashMap<String, Plugin>();
    private static File updateDirectory = null;
    private final SimpleCommandMap commandMap;
    private final Map<String, Permission> permissions = new HashMap<String, Permission>();
    private final Map<Boolean, Set<Permission>> defaultPerms = new LinkedHashMap<Boolean, Set<Permission>>();
    private final Map<String, Map<Permissible, Boolean>> permSubs = new HashMap<String, Map<Permissible, Boolean>>();
    private final Map<Boolean, Map<Permissible, Boolean>> defSubs = new HashMap<Boolean, Map<Permissible, Boolean>>();
    private boolean useTimings = false;

    public SimplePluginManager(Server instance, SimpleCommandMap commandMap) {
        server = instance;
        this.commandMap = commandMap;

        defaultPerms.put(true, new HashSet<Permission>());
        defaultPerms.put(false, new HashSet<Permission>());
    }

    /**
     * Registers the specified plugin loader
     *
     * @param loader Class name of the PluginLoader to register
     * @throws IllegalArgumentException Thrown when the given Class is not a valid PluginLoader
     */
    public void registerInterface(Class<? extends PluginLoader> loader) throws IllegalArgumentException {
        PluginLoader instance;

        if (PluginLoader.class.isAssignableFrom(loader)) {
            Constructor<? extends PluginLoader> constructor;

            try {
                constructor = loader.getConstructor(Server.class);
                instance = constructor.newInstance(server);
            } catch (NoSuchMethodException ex) {
                String className = loader.getName();

                throw new IllegalArgumentException(String.format("Class %s does not have a public %s(Server) constructor", className, className), ex);
            } catch (Exception ex) {
                throw new IllegalArgumentException(String.format("Unexpected exception %s while attempting to construct a new instance of %s", ex.getClass().getName(), loader.getName()), ex);
            }
        } else {
            throw new IllegalArgumentException(String.format("Class %s does not implement interface PluginLoader", loader.getName()));
        }

        Pattern[] patterns = instance.getPluginFileFilters();

        synchronized (this) {
            for (Pattern pattern : patterns) {
                fileAssociations.put(pattern, instance);
            }
        }
    }

    public class PluginNode {
        public List<DependencyEdge> inEdges = new ArrayList<DependencyEdge>();
        public List<DependencyEdge> outEdges = new ArrayList<DependencyEdge>();
        private String name;
        private File file;
        public PluginNode(String name, File f){
            this.name = name;
            this.file = f;
        }

        public void addOutEdge(PluginNode n){
            DependencyEdge e = new DependencyEdge(this, n);
            outEdges.add(e);
            n.addInEdge(e);

        }

        public void addInEdge(DependencyEdge e){
            inEdges.add(e);
        }

        public List<DependencyEdge> getInEdges(){
            return inEdges;
        }

        public List<DependencyEdge> getOutEdges(){
            return outEdges;
        }

        public boolean hasInEdges(){
            return !(inEdges.size() == 0);
        }
        
        public File getFile(){
            return file;
        }
        
        @Override
        public String toString(){
            return name;
        }
        
        @Override
        public boolean equals(Object other){
            return other.toString().equals(this.toString());
        }
        
        @Override
        public int hashCode(){
            return this.toString().hashCode();
        }
    }

    public class DependencyEdge {

        private PluginNode from;
        private PluginNode to;

        public DependencyEdge(PluginNode from, PluginNode to) {
            this.from = from;
            this.to = to;
        }

        public PluginNode getFrom() {
            return from;
        }


        public PluginNode getTo() {
            return to;
        }

    }
    
    
    
    /**
     * Loads the plugins contained within the specified directory
     *
     * @param directory Directory to check for plugins
     * @return A list of all plugins loaded
     */
    public Plugin[] loadPlugins(File directory) {
        Validate.notNull(directory, "Directory cannot be null");
        Validate.isTrue(directory.isDirectory(), "Directory must be a directory");

        List<Plugin> result = new ArrayList<Plugin>();
        Set<Pattern> filters = fileAssociations.keySet();

        if (!(server.getUpdateFolder().equals(""))) {
            updateDirectory = new File(directory, server.getUpdateFolder());
        }

        Set<String> loadedPlugins = new HashSet<String>();
        Map<PluginNode, Collection<String>> dependencies = new HashMap<PluginNode, Collection<String>>();
        Map<PluginNode, Collection<String>> softDependencies = new HashMap<PluginNode, Collection<String>>();
        List<PluginNode> pluginGraph = new ArrayList<PluginNode>();
        // This is where it figures out all possible plugins
        for (File file : directory.listFiles()) {
            PluginLoader loader = null;
            for (Pattern filter : filters) {
                Matcher match = filter.matcher(file.getName());
                if (match.find()) {
                    loader = fileAssociations.get(filter);
                }
            }

            if (loader == null) continue;

            PluginDescriptionFile description = null;
            try {
                description = loader.getPluginDescription(file);
            } catch (InvalidDescriptionException ex) {
                server.getLogger().log(Level.SEVERE, "Could not load '" + file.getPath() + "' in folder '" + directory.getPath() + "'", ex);
                continue;
            }
            PluginNode node = new PluginNode(description.getName(), file);
            pluginGraph.add(node);
            Collection<String> softDependencySet = description.getSoftDepend();
            if (softDependencySet != null) {
                softDependencies.put(node, new LinkedList<String>(softDependencySet));
            }

            Collection<String> dependencySet = description.getDepend();
            if (dependencySet != null) {
                dependencies.put(node, new LinkedList<String>(dependencySet));
            }
        }
        server.getLogger().log(Level.INFO, ChatColor.BLUE + pluginGraph.toString());

        //First off, we are going to check dependencies of all plugins, and therefore building the dependency graph
        //This graph shows how dependencies are in relation to eachother, and using a Topological Sort algorithm
        //We can find out how to load the plugins.
        for(Iterator<PluginNode> it = pluginGraph.iterator(); it.hasNext();){
            PluginNode fromNode = it.next();
            Collection<String> softDependencySet = softDependencies.get(fromNode);
            Collection<String> dependencySet = dependencies.get(fromNode);
            server.getLogger().log(Level.INFO, ChatColor.GREEN + fromNode.toString());
            
            
            if(dependencySet != null){
                server.getLogger().log(Level.INFO, "Deps: " + dependencySet.toString());
                for(String dep: dependencySet){
                    System.out.println(dep);
                    int dependencyIndex;
                    if((dependencyIndex = pluginGraph.indexOf(new PluginNode(dep, null))) != -1){
                        PluginNode toNode = pluginGraph.get(dependencyIndex);
                        fromNode.addOutEdge(toNode);
                    }else{
                        server.getLogger().log(Level.SEVERE, "Could not load '" +  fromNode.toString() + "' missing dependency");
                        it.remove();
                        continue;
                    }
                }
            }
            
            //Yes, i am turning soft dependencies into hard ones
            //minor difference, we do not exclude a plugin from loading when a soft
            //dependency is not met
            if(softDependencySet != null){
                server.getLogger().log(Level.INFO, "SoftDeps: " + softDependencySet.toString());
                for(String dep: softDependencySet){
                    if(pluginGraph.contains(new PluginNode(dep, null))){
                        PluginNode toNode = pluginGraph.get(pluginGraph.indexOf(new PluginNode(dep, null)));
                        fromNode.addOutEdge(toNode);
                    }else{
                        server.getLogger().log(Level.INFO, "Loading '" +  fromNode.toString() + "' without soft dependency " + dep);
                    }
                }
            }
           
        }
        
        //Sort the plugins using the topilogical sorter, in reversed order
        List<PluginNode> sortedPluginList = sortPluginListTopilogical(pluginGraph);
        //Sorted list in right order :)
        Collections.reverse(sortedPluginList);
        server.getLogger().info("Gesorteerde Plugins" + sortedPluginList.toString());
        //And load the plugins
        for(PluginNode pnd : sortedPluginList){
            try {
                server.getLogger().info("Loading plugin " + pnd);
                result.add(loadPlugin(pnd.getFile()));
                loadedPlugins.add(pnd.toString());
            } catch (InvalidPluginException ex) {
                server.getLogger().log(Level.SEVERE, "Could not load '" + pnd.getFile().getPath() + "' in folder '" + directory.getPath() + "'", ex);
            }
        }

        return result.toArray(new Plugin[result.size()]);
    }
    
    /**
     * This is the sorter, that will take a list of pluginNodes
     * and using the first Topological algoritm defined by wikipedia (first one)
     * determins a ordering to load the plugins
     * http://en.wikipedia.org/wiki/Topological_sort
     * and with some help from
     * http://stackoverflow.com/questions/2739392/sample-directed-graph-and-topological-sort-code
     * @param pluginList
     * @return
     */
    public List<PluginNode> sortPluginListTopilogical(List<PluginNode> pluginList){
        List<PluginNode> resultList = new ArrayList<PluginNode>();
        List<PluginNode> nonIncomingEdgesList = new ArrayList<PluginNode>();
        
        for(PluginNode p : pluginList){
            if(!p.hasInEdges()){
                nonIncomingEdgesList.add(p);
                System.out.println(p);
            }
        }
        
        while(!nonIncomingEdgesList.isEmpty()){
            PluginNode nodeFrom = nonIncomingEdgesList.iterator().next();
            nonIncomingEdgesList.remove(nodeFrom);
            
            resultList.add(nodeFrom);
            for(Iterator<DependencyEdge> it = nodeFrom.getOutEdges().iterator(); it.hasNext();){
                DependencyEdge de = it.next();
                PluginNode nodeTo = de.getTo();
                it.remove();
                nodeTo.getInEdges().remove(de);
                
                if(nodeTo.getInEdges().isEmpty()){
                    nonIncomingEdgesList.add(nodeTo);
                }
                
            }
        }
        
        for(PluginNode pnd : pluginList){
            if(!pnd.getInEdges().isEmpty()){
               //TODO: do something, you cannot just stand here and watch
                server.getLogger().log(Level.SEVERE, "Could not determin a way to load '" + pnd.getFile().getPath() + "': circular dependency detected"); 
            }
        }
        
        return resultList;
    }
    /**
     * Loads the plugin in the specified file
     * <p />
     * File must be valid according to the current enabled Plugin interfaces
     *
     * @param file File containing the plugin to load
     * @return The Plugin loaded, or null if it was invalid
     * @throws InvalidPluginException Thrown when the specified file is not a valid plugin
     * @throws UnknownDependencyException If a required dependency could not be found
     */
    public synchronized Plugin loadPlugin(File file) throws InvalidPluginException, UnknownDependencyException {
        Validate.notNull(file, "File cannot be null");

        checkUpdate(file);

        Set<Pattern> filters = fileAssociations.keySet();
        Plugin result = null;

        for (Pattern filter : filters) {
            String name = file.getName();
            Matcher match = filter.matcher(name);

            if (match.find()) {
                PluginLoader loader = fileAssociations.get(filter);

                result = loader.loadPlugin(file);
            }
        }

        if (result != null) {
            plugins.add(result);
            lookupNames.put(result.getDescription().getName(), result);
        }

        return result;
    }

    private void checkUpdate(File file) {
        if (updateDirectory == null || !updateDirectory.isDirectory()) {
            return;
        }

        File updateFile = new File(updateDirectory, file.getName());
        if (updateFile.isFile() && FileUtil.copy(updateFile, file)) {
            updateFile.delete();
        }
    }

    /**
     * Checks if the given plugin is loaded and returns it when applicable
     * <p />
     * Please note that the name of the plugin is case-sensitive
     *
     * @param name Name of the plugin to check
     * @return Plugin if it exists, otherwise null
     */
    public synchronized Plugin getPlugin(String name) {
        return lookupNames.get(name);
    }

    public synchronized Plugin[] getPlugins() {
        return plugins.toArray(new Plugin[0]);
    }

    /**
     * Checks if the given plugin is enabled or not
     * <p />
     * Please note that the name of the plugin is case-sensitive.
     *
     * @param name Name of the plugin to check
     * @return true if the plugin is enabled, otherwise false
     */
    public boolean isPluginEnabled(String name) {
        Plugin plugin = getPlugin(name);

        return isPluginEnabled(plugin);
    }

    /**
     * Checks if the given plugin is enabled or not
     *
     * @param plugin Plugin to check
     * @return true if the plugin is enabled, otherwise false
     */
    public boolean isPluginEnabled(Plugin plugin) {
        if ((plugin != null) && (plugins.contains(plugin))) {
            return plugin.isEnabled();
        } else {
            return false;
        }
    }

    public void enablePlugin(final Plugin plugin) {
        if (!plugin.isEnabled()) {
            List<Command> pluginCommands = PluginCommandYamlParser.parse(plugin);

            if (!pluginCommands.isEmpty()) {
                commandMap.registerAll(plugin.getDescription().getName(), pluginCommands);
            }

            try {
                plugin.getPluginLoader().enablePlugin(plugin);
            } catch (Throwable ex) {
                server.getLogger().log(Level.SEVERE, "Error occurred (in the plugin loader) while enabling " + plugin.getDescription().getFullName() + " (Is it up to date?)", ex);
            }

            HandlerList.bakeAll();
        }
    }

    public void disablePlugins() {
        for (Plugin plugin : getPlugins()) {
            disablePlugin(plugin);
        }
    }

    public void disablePlugin(final Plugin plugin) {
        if (plugin.isEnabled()) {
            try {
                plugin.getPluginLoader().disablePlugin(plugin);
            } catch (Throwable ex) {
                server.getLogger().log(Level.SEVERE, "Error occurred (in the plugin loader) while disabling " + plugin.getDescription().getFullName() + " (Is it up to date?)", ex);
            }

            try {
                server.getScheduler().cancelTasks(plugin);
            } catch (Throwable ex) {
                server.getLogger().log(Level.SEVERE, "Error occurred (in the plugin loader) while cancelling tasks for " + plugin.getDescription().getFullName() + " (Is it up to date?)", ex);
            }

            try {
                server.getServicesManager().unregisterAll(plugin);
            } catch (Throwable ex) {
                server.getLogger().log(Level.SEVERE, "Error occurred (in the plugin loader) while unregistering services for " + plugin.getDescription().getFullName() + " (Is it up to date?)", ex);
            }

            try {
                HandlerList.unregisterAll(plugin);
            } catch (Throwable ex) {
                server.getLogger().log(Level.SEVERE, "Error occurred (in the plugin loader) while unregistering events for " + plugin.getDescription().getFullName() + " (Is it up to date?)", ex);
            }

            try {
                server.getMessenger().unregisterIncomingPluginChannel(plugin);
                server.getMessenger().unregisterOutgoingPluginChannel(plugin);
            } catch(Throwable ex) {
                server.getLogger().log(Level.SEVERE, "Error occurred (in the plugin loader) while unregistering plugin channels for " + plugin.getDescription().getFullName() + " (Is it up to date?)", ex);
            }
        }
    }

    public void clearPlugins() {
        synchronized (this) {
            disablePlugins();
            plugins.clear();
            lookupNames.clear();
            HandlerList.unregisterAll();
            fileAssociations.clear();
            permissions.clear();
            defaultPerms.get(true).clear();
            defaultPerms.get(false).clear();
        }
    }

    /**
     * Calls an event with the given details
     *
     * @param event Event details
     */
    public synchronized void callEvent(Event event) {
        HandlerList handlers = event.getHandlers();
        RegisteredListener[] listeners = handlers.getRegisteredListeners();

        for (RegisteredListener registration : listeners) {
            if (!registration.getPlugin().isEnabled()) {
                continue;
            }

            try {
                registration.callEvent(event);
            } catch (AuthorNagException ex) {
                Plugin plugin = registration.getPlugin();

                if (plugin.isNaggable()) {
                    plugin.setNaggable(false);

                    String author = "<NoAuthorGiven>";

                    if (plugin.getDescription().getAuthors().size() > 0) {
                        author = plugin.getDescription().getAuthors().get(0);
                    }
                    server.getLogger().log(Level.SEVERE, String.format(
                            "Nag author: '%s' of '%s' about the following: %s",
                            author,
                            plugin.getDescription().getName(),
                            ex.getMessage()
                            ));
                }
            } catch (Throwable ex) {
                server.getLogger().log(Level.SEVERE, "Could not pass event " + event.getEventName() + " to " + registration.getPlugin().getDescription().getName(), ex);
            }
        }
    }

    public void registerEvents(Listener listener, Plugin plugin) {
        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Plugin attempted to register " + listener + " while not enabled");
        }

        for (Map.Entry<Class<? extends Event>, Set<RegisteredListener>> entry : plugin.getPluginLoader().createRegisteredListeners(listener, plugin).entrySet()) {
            getEventListeners(getRegistrationClass(entry.getKey())).registerAll(entry.getValue());
        }

    }

    public void registerEvent(Class<? extends Event> event, Listener listener, EventPriority priority, EventExecutor executor, Plugin plugin) {
        registerEvent(event, listener, priority, executor, plugin, false);
    }

    /**
     * Registers the given event to the specified listener using a directly passed EventExecutor
     *
     * @param event Event class to register
     * @param listener PlayerListener to register
     * @param priority Priority of this event
     * @param executor EventExecutor to register
     * @param plugin Plugin to register
     * @param ignoreCancelled Do not call executor if event was already cancelled
     */
    public void registerEvent(Class<? extends Event> event, Listener listener, EventPriority priority, EventExecutor executor, Plugin plugin, boolean ignoreCancelled) {
        Validate.notNull(listener, "Listener cannot be null");
        Validate.notNull(priority, "Priority cannot be null");
        Validate.notNull(executor, "Executor cannot be null");
        Validate.notNull(plugin, "Plugin cannot be null");

        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Plugin attempted to register " + event + " while not enabled");
        }

        if (useTimings) {
            getEventListeners(event).register(new TimedRegisteredListener(listener, executor, priority, plugin, ignoreCancelled));
        } else {
            getEventListeners(event).register(new RegisteredListener(listener, executor, priority, plugin, ignoreCancelled));
        }
    }

    private HandlerList getEventListeners(Class<? extends Event> type) {
        try {
            Method method = getRegistrationClass(type).getDeclaredMethod("getHandlerList");
            method.setAccessible(true);
            return (HandlerList) method.invoke(null);
        } catch (Exception e) {
            throw new IllegalPluginAccessException(e.toString());
        }
    }

    private Class<? extends Event> getRegistrationClass(Class<? extends Event> clazz) {
        try {
            clazz.getDeclaredMethod("getHandlerList");
            return clazz;
        } catch (NoSuchMethodException e) {
            if (clazz.getSuperclass() != null
                    && !clazz.getSuperclass().equals(Event.class)
                    && Event.class.isAssignableFrom(clazz.getSuperclass())) {
                return getRegistrationClass(clazz.getSuperclass().asSubclass(Event.class));
            } else {
                throw new IllegalPluginAccessException("Unable to find handler list for event " + clazz.getName());
            }
        }
    }

    public Permission getPermission(String name) {
        return permissions.get(name.toLowerCase());
    }

    public void addPermission(Permission perm) {
        String name = perm.getName().toLowerCase();

        if (permissions.containsKey(name)) {
            throw new IllegalArgumentException("The permission " + name + " is already defined!");
        }

        permissions.put(name, perm);
        calculatePermissionDefault(perm);
    }

    public Set<Permission> getDefaultPermissions(boolean op) {
        return ImmutableSet.copyOf(defaultPerms.get(op));
    }

    public void removePermission(Permission perm) {
        removePermission(perm.getName().toLowerCase());
    }

    public void removePermission(String name) {
        permissions.remove(name);
    }

    public void recalculatePermissionDefaults(Permission perm) {
        if (permissions.containsValue(perm)) {
            defaultPerms.get(true).remove(perm);
            defaultPerms.get(false).remove(perm);

            calculatePermissionDefault(perm);
        }
    }

    private void calculatePermissionDefault(Permission perm) {
        if ((perm.getDefault() == PermissionDefault.OP) || (perm.getDefault() == PermissionDefault.TRUE)) {
            defaultPerms.get(true).add(perm);
            dirtyPermissibles(true);
        }
        if ((perm.getDefault() == PermissionDefault.NOT_OP) || (perm.getDefault() == PermissionDefault.TRUE)) {
            defaultPerms.get(false).add(perm);
            dirtyPermissibles(false);
        }
    }

    private void dirtyPermissibles(boolean op) {
        Set<Permissible> permissibles = getDefaultPermSubscriptions(op);

        for (Permissible p : permissibles) {
            p.recalculatePermissions();
        }
    }

    public void subscribeToPermission(String permission, Permissible permissible) {
        String name = permission.toLowerCase();
        Map<Permissible, Boolean> map = permSubs.get(name);

        if (map == null) {
            map = new WeakHashMap<Permissible, Boolean>();
            permSubs.put(name, map);
        }

        map.put(permissible, true);
    }

    public void unsubscribeFromPermission(String permission, Permissible permissible) {
        String name = permission.toLowerCase();
        Map<Permissible, Boolean> map = permSubs.get(name);

        if (map != null) {
            map.remove(permissible);

            if (map.isEmpty()) {
                permSubs.remove(name);
            }
        }
    }

    public Set<Permissible> getPermissionSubscriptions(String permission) {
        String name = permission.toLowerCase();
        Map<Permissible, Boolean> map = permSubs.get(name);

        if (map == null) {
            return ImmutableSet.of();
        } else {
            return ImmutableSet.copyOf(map.keySet());
        }
    }

    public void subscribeToDefaultPerms(boolean op, Permissible permissible) {
        Map<Permissible, Boolean> map = defSubs.get(op);

        if (map == null) {
            map = new WeakHashMap<Permissible, Boolean>();
            defSubs.put(op, map);
        }

        map.put(permissible, true);
    }

    public void unsubscribeFromDefaultPerms(boolean op, Permissible permissible) {
        Map<Permissible, Boolean> map = defSubs.get(op);

        if (map != null) {
            map.remove(permissible);

            if (map.isEmpty()) {
                defSubs.remove(op);
            }
        }
    }

    public Set<Permissible> getDefaultPermSubscriptions(boolean op) {
        Map<Permissible, Boolean> map = defSubs.get(op);

        if (map == null) {
            return ImmutableSet.of();
        } else {
            return ImmutableSet.copyOf(map.keySet());
        }
    }

    public Set<Permission> getPermissions() {
        return new HashSet<Permission>(permissions.values());
    }

    public boolean useTimings() {
        return useTimings;
    }

    /**
     * Sets whether or not per event timing code should be used
     *
     * @param use True if per event timing code should be used
     */
    public void useTimings(boolean use) {
        useTimings = use;
    }
}

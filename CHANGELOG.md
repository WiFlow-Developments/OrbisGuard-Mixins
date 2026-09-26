<h1>Changelog</h1>

<p>All notable changes to OrbisGuard-Mixins will be documented in this file.</p>

<hr>

<h2>[0.10.0] - 2026-09-26</h2>

<h3>Changed</h3>

<p><b>Hytale 0.6.8 support</b> - All mixins retargeted for the 0.6.8 release server. Crop harvesting, hammer cycling and auto-pickup moved to new call sites upstream.</p>

<p><b>Spawn and fire mixins are required again</b> - <code>WorldSpawnJobSystemsMixin</code>, <code>NPCPluginSpawnMixin</code>, <code>StoreAddEntityMixin</code> and <code>FireFluidTickerMixin</code> now fail at startup if a server update moves their target, instead of quietly turning protection off. Only the log-spam filters stay optional.</p>

<h3>Fixed</h3>

<p><b>Hammer cycling</b> - A denied hammer hit could still cycle the block and cost durability after showing the deny message. Denied hits now leave the block and the hammer alone.</p>

<p><b>Log filters</b> - The spawn beacon, spawn marker and interaction sync log filters match the new log calls again.</p>

<hr>

<h2>[0.9.0] - 2026-05-14</h2>

<h3>Changed</h3>

<p><b>Hytale pre-release support</b> - All mixins updated for the Update 5 pre-release server (JOML vectors, <code>PlayerRef</code>, new <code>performBlockDamage</code> signature).</p>

<p><b>No more copied vanilla code</b> - The 10 mixins that replaced whole methods now wrap them with MixinExtras <code>@WrapMethod</code> and call the original, so upstream changes to those methods come along automatically.</p>

<h3>Notes</h3>

<p>Needs Hyinit. No new server event replaces any of the mixins yet.</p>

<hr>

<h2>[0.8.8] - 2026-03-26</h2>

<h3>Added</h3>

<p><b>NPCDeathItemDropMixin</b> - Enables the new <code>mob-loot</code> region flag from OrbisGuard. Intercepts the NPC death drop system and skips item-spawn generation when a mob dies inside a region with <code>mob-loot deny</code>, covering normal kills and environmental deaths at the mob's death position.</p>

<h3>Fixed</h3>

<p><b>Hytale server update compatibility</b> - All mixins updated for breaking changes in the latest Hytale server release. <code>ItemDurabilityMixin</code> now targets <code>ItemUtils</code> instead of <code>Player</code> after <code>canDecreaseItemStackDurability</code> was moved to a static utility method. <code>BenchWindowMixin</code> adapted for <code>BenchState</code> removal and new 9-parameter <code>feedExtraResourcesSection</code> signature. <code>CraftingManagerMixin</code> updated for <code>ItemContainerState</code> rename to <code>ItemContainerBlock</code> and new position computation via <code>BlockStateInfo</code>. <code>ReturnPortalInteractionMixin</code> updated for <code>getCombinedEverything()</code> replaced by <code>InventoryComponent.getCombined()</code>. <code>TeleporterInteractionMixin</code> updated for <code>ObjectList</code> to <code>List</code> generics change. <code>NPCDeathItemDropMixin</code> rewritten for <code>DropDeathItems</code> changing from a component-added listener to a ticking system, with new deferred corpse removal and drop-once guard logic.</p>

<h3>Notes</h3>

<p>Keep <code>OrbisGuard-Mixins</code> in <code>earlyplugins/</code> so <code>mob-loot</code>, keep-inventory, and other non-ECS protections continue to work alongside the main plugin update.</p>

<hr>

<h2>[0.8.7] - 2026-03-14</h2>

<h3>Changed</h3>

<p><b>Version alignment release</b> - Version bumped to match OrbisGuard 0.8.7 so deployment artifacts line up again.</p>

<h3>Notes</h3>

<p>No mixin logic changes in this release. Keep <code>OrbisGuard-Mixins</code> in <code>earlyplugins/</code> so queued crafting and auto-pickup protections continue to work.</p>

<p>Seat rank-permission support is handled in the main OrbisGuard plugin and API. No additional mixin changes were required.</p>

<hr>

<h2>[0.8.4] - 2026-03-03</h2>

<h3>Performance</h3>

<p><b>All 26 mixins migrated to MethodHandle caching for 10-100x performance improvement</b> - Replaced slow reflection-based hook invocation (<code>getMethod()</code> + <code>invoke()</code>) with high-performance cached MethodHandles. Each mixin now caches the MethodHandle on first use and reuses it for subsequent calls, with bridge identity checking to detect plugin reloads.</p>

<ul>
<li>Performance: ~1-10μs per reflection call → ~0.01-0.1μs with cached MethodHandle</li>
<li>Bridge access: String-based HashMap lookup → integer slot O(1) array access</li>
<li>Error tracking: AtomicLong with rate-limited logging (log first + every 100th error)</li>
</ul>

<h3>Added</h3>

<p><b>RespawnLocationMixin</b> - Enables custom respawn locations per region via <code>respawn-location</code> flag. Redirects <code>Player.getRespawnPosition()</code> to check if the player died in a region with this flag set. If set, overrides the respawn location to the configured coordinates. Falls back to default bed/spawn behavior if not set.</p>

<h3>Changed</h3>

<p><b>26 mixins total</b> (was 25). New: RespawnLocationMixin.</p>

<p>All hook-invoking mixins migrated to ProtectionBridge API with MethodHandle caching. Backward compatibility maintained through deprecated HookRegistry facade.</p>

<h3>Requirements</h3>

<p><b>OrbisGuard 0.8.4</b></p>

<hr>

<h2>[0.8.3] - 2026-02-18</h2>

<h3>Added</h3>

<p><b>HarvestCropInteractionMixin</b> - Blocks scythe/tool-based crop harvesting in protected regions. Scythes use <code>HarvestCropInteraction</code> → <code>FarmingUtil.harvest()</code>, a separate code path from F-key harvesting. Redirects the <code>FarmingUtil.harvest()</code> call and checks <code>block-break</code> permission before allowing the harvest. Returns false on denial which sets <code>InteractionState.Failed</code>, so the client handles it cleanly without needing explicit block resync.</p>

<p><b>CycleBlockGroupInteractionMixin</b> - Adds OrbisGuard protection directly on the base <code>CycleBlockGroupInteraction</code> class. Fixes hammer cycling not being blocked when another plugin (e.g., SimpleClaims) replaces OrbisGuard's codec-registered interaction override. Three redirects: captures player context, checks hammer permission at block read, and skips the <code>setBlock</code> call when denied. Respects protection hooks so claim plugin members can still hammer in their own claims.</p>

<h3>Changed</h3>

<p><b>25 mixins total</b> (was 23). New: HarvestCropInteractionMixin, CycleBlockGroupInteractionMixin.</p>

<h3>Requirements</h3>

<p><b>OrbisGuard 0.8.3</b></p>

<hr>

<h2>[0.8.0] - 2026-02-15</h2>

<h3>Added</h3>

<p><b>FireFluidTickerMixin</b> (pre-release only) - Blocks fire fluid from spreading into protected regions. Hytale's fire is a fluid system that bypasses all ECS events, so this intercepts <code>FluidTicker.spread()</code>. Does nothing on release servers where fire doesn't exist yet.</p>

<p><b>HubPortalInteractionMixin</b> - Blocks hub portal usage in regions with <code>use-portals deny</code>. Covers the remaining portal type that wasn't handled by the existing portal mixins.</p>

<h3>Fixed</h3>

<p><b>Item durability mixin broken</b> - <code>ItemDurabilityMixin</code> used a FIELD redirect on <code>Player.gameMode</code> which was not reliable under the mixin bootstrapper. Rewrote to <code>@Inject</code> at HEAD of <code>canDecreaseItemStackDurability</code>.</p>

<p><b>Auto-pickup mixin broken on latest server</b> - Server changed pickups to go through <code>SpatialStructure.closest()</code> + interaction chains. Rewrote <code>PlayerItemEntityPickupSystemMixin</code> to intercept <code>closest()</code> and return null for denied pickups. Old <code>getInventory</code>/<code>addItemStack</code> redirects removed.</p>

<p><b>CraftingManagerMixin not catching queued recipes</b> - <code>queueCraft()</code> (time-based workbench recipes) never fired <code>CraftRecipeEvent.Pre</code>. Now fires the event before queueing so the ECS system catches both queued and instant crafts.</p>

<p><b>F-key harvest checking wrong flag</b> - <code>BlockHarvestUtilsMixin</code> checked <code>block-break</code> instead of <code>item-pickup</code>. F-key harvest is a pickup, not a break.</p>

<p><b>FireFluidTickerMixin crash on release</b> - Typo in method descriptor (lowercase <code>iii</code> instead of <code>III</code>). Crashed with <code>InvalidMemberDescriptorException</code>.</p>

<p><b>Mobs spawning before plugin loads</b> - Spawn mixins now block all spawns during startup until OrbisGuard registers its hook. New <code>spawnProtectionStartupMode</code> config ("block" default, "allow" for legacy).</p>

<h3>Changed</h3>

<p><b>23 mixins total</b> (was 20). New: FireFluidTickerMixin, HubPortalInteractionMixin.</p>

<h3>Requirements</h3>

<p><b>OrbisGuard 0.8.0</b></p>

<hr>

<h2>[0.7.7] - 2026-01-28</h2>

<h3>Added</h3>

<p><b>Extended portal protection</b> - The <code>use-portals</code> flag now blocks all portal and teleporter types:</p>

<ul>
<li><b>EnterPortalInteractionMixin</b> - Blocks Portal block entry (enter adventure worlds)</li>
<li><b>ReturnPortalInteractionMixin</b> - Blocks PortalReturn block usage (exit to hub)</li>
<li><b>ExitInstanceInteractionMixin</b> - Blocks instance exit interactions</li>
<li><b>TeleportInstanceInteractionMixin</b> - Blocks instance teleportation</li>
<li><b>TeleportConfigInstanceInteractionMixin</b> - Blocks configured instance teleports</li>
</ul>

<h3>Fixed</h3>

<p><b>Mixin message color parsing</b> - Fixed classloader issues where denial messages showed raw color codes (e.g., "&red") instead of colored text. Mixins now include inline fallback parsing when the utility class isn't available yet.</p>

<h3>Requirements</h3>

<p><b>OrbisGuard 0.7.7</b> - Required for portal hook support</p>

<hr>

<h2>[0.7.6] - 2026-01-27</h2>

<p>Internal refactoring. Portal mixins added but hook not yet exposed in main plugin.</p>

<hr>

<h2>[0.7.5] - 2026-01-27</h2>

<h3>Added</h3>

<p><b>BuilderToolsPacketHandlerMixin</b> - Protects regions from Hytale's creative BuilderTools (Paint Brush, Flood Fill, Sculpt Brush, Paste Tool, etc.). Players without build permission can no longer bypass protection using these tools.</p>

<p><b>TeleporterInteractionMixin</b> - Protects regions from teleporter block usage (both F-key and walk-through). Players can be blocked from using teleporters with the <code>use-portals</code> flag.</p>

<h3>Requirements</h3>

<p><b>OrbisGuard 0.7.5</b> - Required for BuilderTools hook support</p>

<hr>

<h2>[0.7.4] - 2026-01-26</h2>

<p>Version bump to match OrbisGuard 0.7.4. No mixin changes.</p>

<hr>

<h2>[0.7.3] - 2026-01-25</h2>

<h3>Added</h3>

<p><b>BenchWindowMixin</b> - Fixes workbench chest access exploit. Players could place a workbench adjacent to a protected region and use the crafting table's material search feature to access chests inside. The mixin now filters container access through OrbisGuard's <code>chest-access</code> permission check.</p>

<p><b>CraftingManagerMixin</b> - Additional crafting protection layer for edge cases not covered by the ECS event system.</p>

<h3>Removed</h3>

<p>The following mixins have been removed and replaced with cleaner codec-based interaction overrides in the main OrbisGuard plugin (no longer requires mixins):</p>

<ul>
  <li><b>CycleBlockGroupInteractionMixin</b> - Hammer cycling now handled by <code>OrbisCycleBlockGroupInteraction</code></li>
  <li><b>PlaceFluidInteractionMixin</b> - Fluid placement now handled by <code>OrbisPlaceFluidInteraction</code></li>
  <li><b>SeatingInteractionMixin</b> - Chair/bench sitting now handled by <code>OrbisSeatingInteraction</code></li>
  <li><b>ChangeStateInteractionMixin</b> - Campfire/lantern toggles now handled by <code>OrbisChangeStateInteraction</code></li>
</ul>

<p>This reduces the mixin surface area and improves compatibility with other mods.</p>

<h3>Changed</h3>

<p><b>Mixin count reduced</b> - From 17 mixins to 15 active mixins. Less bytecode patching = fewer conflicts.</p>

<p><b>RespawnLocationMixin disabled</b> - Temporarily disabled pending API stabilization. File renamed to <code>.disabled</code>.</p>

<h3>Requirements</h3>

<p><b>OrbisGuard 0.7.3</b> - Required for codec-based interaction support</p>

<p><b>Hyinit</b> - Mixin bootstrapper for Hytale</p>

<hr>

<h2>[0.7.2] - 2026-01-24</h2>

<p><b>Initial Public Release</b> - Companion mixin module for OrbisGuard.</p>

<h3>Protection Coverage</h3>

<p>Closes all known protection bypasses that aren't covered by Hytale's plugin API:</p>

<p><b>Item Pickup</b> - Blocks both auto-pickup (walking over items) and interactive pickup (F-key)</p>

<p><b>Hammer Cycling</b> - Prevents cycling block variants with the hammer tool</p>

<p><b>F-Key Harvesting</b> - Blocks instant crop/plant harvesting</p>

<p><b>Fluid Placement</b> - Prevents bucket and fluid source placement</p>

<p><b>Explosion Damage</b> - Protects blocks from all explosion types</p>

<p><b>Command Blocking</b> - Full coverage for blocked-cmds and allowed-cmds flags</p>

<p><b>Seating</b> - Prevents sitting on chairs, benches, and other seatable blocks</p>

<p><b>Block State Changes</b> - Blocks campfire lighting/extinguishing, lantern toggling</p>

<p><b>Mob Spawning</b> - Controls natural spawns, spawn markers, and NPC spawns</p>

<p><b>Keep Inventory</b> - Enables the keep-inventory flag on death</p>

<p><b>Invincible Items</b> - Enables the item-durability flag to prevent durability loss</p>

<h3>Mixins Included</h3>

<p>PlayerItemEntityPickupSystemMixin, CycleBlockGroupInteractionMixin, BlockHarvestUtilsMixin, PlaceFluidInteractionMixin, ExplosionBlockDamageMixin, CommandManagerMixin, SeatingInteractionMixin, ChangeStateInteractionMixin, SpawnMarkerEntityMixin, WorldSpawnJobSystemsMixin, NPCPluginSpawnMixin, FloodFillPositionSelectorMixin, InteractionEntryMixin, InteractionChainMixin, StoreAddEntityMixin, DeathItemDropMixin, ItemDurabilityMixin</p>

<h3>Requirements</h3>

<p><b>OrbisGuard 0.7.2</b> - Main region protection plugin</p>

<p><b>Hyinit</b> - Mixin bootstrapper for Hytale</p>

<h3>Installation</h3>

<p>Place <code>OrbisGuard-Mixins.jar</code> in your server's <code>earlyplugins/</code> folder (not <code>mods/</code>). Launch the server through Hyinit with the Hyinit jar next to <code>HytaleServer.jar</code>.</p>

<h3>Compatibility</h3>

<p>Fixed System.properties pollution that could cause ClassCastException in other mods. All boolean flags now use String values, and the hook registry is properly documented for third-party compatibility.</p>

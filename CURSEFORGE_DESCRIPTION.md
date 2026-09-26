<h1>OrbisGuard-Mixins</h1>

<p><b>Companion mixin module for <a href="https://curseforge.com/hytale/mods/orbisguard">OrbisGuard</a></b> - closes all known protection bypasses.</p>

<p>OrbisGuard-Mixins hooks into server internals that aren't exposed by Hytale's plugin API, providing complete region protection coverage. Without this module, players can bypass certain protections through game mechanics like auto-pickup, hammer cycling, and F-key harvesting.</p>

<hr>

<h2>Requirements</h2>

<p><b><a href="https://curseforge.com/hytale/mods/orbisguard">OrbisGuard</a></b> - The main region protection plugin (0.6.0+)</p>

<p><b><a href="https://github.com/IroriPowered/hyinit">Hyinit</a></b> - Mixin bootstrapper for Hytale</p>

<hr>

<h2>Installation</h2>

<p>1. Download <code>OrbisGuard-Mixins-x.x.x.jar</code></p>

<p>2. Place in your server's <code>earlyplugins/</code> folder (NOT <code>mods/</code>)</p>

<p>3. Launch the server through <code>Hyinit-X.X.X.jar</code> instead of <code>HytaleServer.jar</code>. Keep Hyinit next to <code>HytaleServer.jar</code>, not in <code>earlyplugins/</code>.</p>

<p>4. Restart the server</p>

<p>OrbisGuard will automatically detect the mixins module and display <code>[+] Mixins loaded</code> in the startup banner.</p>

<hr>

<h2>What It Protects</h2>

<table>
<tr><th>Protection</th><th>Without Mixins</th><th>With Mixins</th></tr>
<tr><td>Auto item pickup</td><td>Bypassed</td><td>Blocked</td></tr>
<tr><td>Interactive pickup (F-key)</td><td>Bypassed</td><td>Blocked</td></tr>
<tr><td>Hammer block cycling</td><td>Bypassed</td><td>Blocked</td></tr>
<tr><td>F-key crop harvesting</td><td>Bypassed</td><td>Blocked</td></tr>
<tr><td>Bucket/fluid placement</td><td>Bypassed</td><td>Blocked</td></tr>
<tr><td>Explosion block damage</td><td>Bypassed</td><td>Blocked</td></tr>
<tr><td>Command blocking</td><td>Limited</td><td>Full coverage</td></tr>
<tr><td>Seating on blocks</td><td>Bypassed</td><td>Blocked</td></tr>
<tr><td>Campfire/lantern toggle</td><td>Bypassed</td><td>Blocked</td></tr>
<tr><td>Mob spawning control</td><td>Limited</td><td>Full coverage</td></tr>
<tr><td>Keep inventory on death</td><td>Not possible</td><td>Works</td></tr>
<tr><td>Durability loss prevention</td><td>Not possible</td><td>Works</td></tr>
</table>

<hr>

<h2>Mixin List</h2>

<table>
<tr><th>Mixin</th><th>Purpose</th></tr>
<tr><td>PlayerItemEntityPickupSystemMixin</td><td>Blocks auto and manual item pickup</td></tr>
<tr><td>CycleBlockGroupInteractionMixin</td><td>Prevents hammer cycling blocks</td></tr>
<tr><td>BlockHarvestUtilsMixin</td><td>Blocks F-key harvesting</td></tr>
<tr><td>PlaceFluidInteractionMixin</td><td>Prevents bucket/fluid use</td></tr>
<tr><td>ExplosionBlockDamageMixin</td><td>Protects blocks from explosions</td></tr>
<tr><td>CommandManagerMixin</td><td>Full command blocking support</td></tr>
<tr><td>SeatingInteractionMixin</td><td>Prevents sitting on protected blocks</td></tr>
<tr><td>ChangeStateInteractionMixin</td><td>Blocks campfire/lantern toggling</td></tr>
<tr><td>SpawnMarkerEntityMixin</td><td>Controls mob spawning</td></tr>
<tr><td>WorldSpawnJobSystemsMixin</td><td>Controls natural mob spawning</td></tr>
<tr><td>NPCPluginSpawnMixin</td><td>Controls NPC/plugin spawns</td></tr>
<tr><td>FloodFillPositionSelectorMixin</td><td>Protects against area tools</td></tr>
<tr><td>InteractionEntryMixin</td><td>Core interaction protection</td></tr>
<tr><td>InteractionChainMixin</td><td>Chained interaction protection</td></tr>
<tr><td>StoreAddEntityMixin</td><td>Entity addition control</td></tr>
<tr><td>DeathItemDropMixin</td><td>Enables keep-inventory flag</td></tr>
<tr><td>ItemDurabilityMixin</td><td>Enables invincible-items flag</td></tr>
</table>

<hr>

<h2>Flags Enabled by Mixins</h2>

<p>These OrbisGuard flags require the mixins module to function:</p>

<p><code>item-pickup</code> - Block item pickup (auto)</p>

<p><code>item-pickup-manual</code> - Block F-key pickup</p>

<p><code>hammer</code> - Block hammer cycling</p>

<p><code>blocked-cmds</code> / <code>allowed-cmds</code> - Full command blocking</p>

<p><code>seat</code> - Block sitting on chairs/benches</p>

<p><code>mob-spawning</code> - Control all mob spawns</p>

<p><code>keep-inventory</code> - Keep items on death</p>

<p><code>invincible-items</code> - Prevent durability loss</p>

<hr>

<h2>Support</h2>

<p><b>Issues:</b> <a href="https://github.com/WiFlow-Developments/OrbisGuard-Mixins/issues">GitHub Issues</a></p>

<p><b>Discord:</b> <a href="https://discord.gg/wiflow">WiFlow's Discord</a></p>

<hr>

<p><i>OrbisGuard-Mixins is optional but highly recommended for production servers. Without it, knowledgeable players can bypass certain protections.</i></p>

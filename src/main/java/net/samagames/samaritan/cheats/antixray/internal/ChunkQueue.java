/*
 * Copyright (C) 2011-2014 lishid.  All rights reserved.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation,  version 3.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package net.samagames.samaritan.cheats.antixray.internal;


import net.minecraft.server.v1_8_R3.*;
import net.samagames.api.shadows.play.server.PacketChunkDataBulk;
import net.samagames.samaritan.cheats.antixray.Orebfuscator;
import net.samagames.samaritan.cheats.antixray.OrebfuscatorConfig;
import net.samagames.samaritan.cheats.antixray.hook.ChunkProcessingThread;
import net.samagames.samaritan.cheats.antixray.obfuscation.Calculations;
import net.samagames.samaritan.cheats.antixray.obfuscation.MinecraftBlock;
import net.samagames.samaritan.cheats.antixray.obfuscation.ProximityHider;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

//Volatile

public class ChunkQueue extends LinkedList<ChunkCoordIntPair> {
    private static final long serialVersionUID = -1928681564741152336L;
    List<ChunkCoordIntPair> internalQueue = Collections.synchronizedList(new LinkedList<ChunkCoordIntPair>());
    List<ChunkCoordIntPair> outputQueue = Collections.synchronizedList(new LinkedList<ChunkCoordIntPair>());
    List<ChunkCoordIntPair> processingQueue = Collections.synchronizedList(new LinkedList<ChunkCoordIntPair>());
    PacketPlayOutMapChunkBulk lastPacket;

    CraftPlayer player;

    Thread thread;
    AtomicBoolean kill = new AtomicBoolean(false);

    @SuppressWarnings({"unchecked", "rawtypes"})
    public ChunkQueue(CraftPlayer player, List previousEntries) {
        this.player = player;
        internalQueue.addAll(previousEntries);
    }

    @Override
    public boolean remove(Object arg0) {
        return internalQueue.remove(arg0) || processingQueue.remove(arg0) || outputQueue.remove(arg0);
    }

    // Called when the queue should be cleared
    @Override
    public void clear() {
        // Clear the internal queue
        internalQueue.clear();
        super.clear();
    }

    // Called when new chunks are queued
    @Override
    public boolean add(ChunkCoordIntPair e) {
        // Move everything into the internal queue
        return internalQueue.add(e);

        // return super.add(e);
    }

    // Called when the list should be sorted
    @Override
    public Object[] toArray() {
        sort();
        // Return the old array to be sorted
        return internalQueue.toArray();
    }

    // This checks for chunks in the queue
    @Override
    public boolean contains(Object o) {
        // Checks whether the coords are actually in
        return internalQueue.contains(o) || processingQueue.contains(o);
    }

    @Override
    public boolean isEmpty() {
        try {
            // If the player is gone, then don't waste time
            if (player.getHandle().playerConnection.isDisconnected()) {
                // Cleanup all queues
                internalQueue.clear();
                processingQueue.clear();
                outputQueue.clear();
                lastPacket = null;
            } else {
                // Process outputs and inputs
                processOutput();
                processInput();
            }
        } catch (Exception e) {
            Orebfuscator.log(e);
        }
        return true;
    }

    public void sort() {
        // Sort the internal array according to CB - See PlayerChunkMap.movePlayer(EntityPlayer entityplayer)
        java.util.Collections.sort(internalQueue, new ChunkCoordComparator(player.getHandle()));
    }

    public void FinishedProcessing(PacketChunkDataBulk packetMapChunkBulk) {
        try {
            packetMapChunkBulk.writePacket(lastPacket);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (lastPacket != null) {
            player.getHandle().playerConnection.sendPacket(lastPacket);
            // Remove reference to the packet so it can be freed when possible
            lastPacket = null;
        }
        outputQueue.addAll(processingQueue);
        processingQueue.clear();
    }

    private void processOutput() {
        // Chunk packet finished processing, output relevant packets
        while (!outputQueue.isEmpty()) {
            // Get the chunk coordinate
            ChunkCoordIntPair chunk = outputQueue.remove(0);

            // Check if the chunk is ready
            if (chunk != null && player.getHandle().world.isLoaded(new BlockPosition(chunk.x << 4, 0, chunk.z << 4))) {
                // Get all the TileEntities in the chunk
                @SuppressWarnings("rawtypes")
                List tileEntities = ((WorldServer) player.getHandle().world).getTileEntities(chunk.x * 16, 0, chunk.z * 16, chunk.x * 16 + 16, 256, chunk.z * 16 + 16);

                Set<MinecraftBlock> signs = Calculations.getSignsList(player, chunk.x, chunk.z);
                for (Object o : tileEntities) {
                    // Send out packet for the tile entity data
                    this.updateTileEntity(signs, (TileEntity) o);
                }

                // Start tracking entities in the chunk
                ((WorldServer) player.getHandle().world).getTracker().a(player.getHandle(), player.getHandle().world.getChunkAt(chunk.x, chunk.z));
            }

            // Force an update on ProximityHider
            if (OrebfuscatorConfig.UseProximityHider) {
                ProximityHider.playerMoved(player, player.getLocation().add(1, 1, 1));
            }
        }
    }

    private void processInput() {
        // Queue next chunk packet out
        if (processingQueue.isEmpty() && !internalQueue.isEmpty()) {
            // Check if player's output queue has a lot of stuff waiting to be sent. If so, don't process and wait.

            // A list to queue chunks
            List<Chunk> chunks = new LinkedList<Chunk>();

            World world = player.getHandle().world;
            WorldServer worldServer = (WorldServer) world;
            int i = 0;
            // Queue up to 5 chunks
            while (internalQueue.size() > i && chunks.size() < 5) {
                // Dequeue a chunk from input
                ChunkCoordIntPair chunkcoordintpair = internalQueue.get(i);

                // Check not null
                if (chunkcoordintpair == null) {
                    internalQueue.remove(i);
                    continue;
                }
                // Check if the chunk is loaded
                if (world.N().isChunkLoaded(chunkcoordintpair.x, chunkcoordintpair.z)) {
                    Chunk chunk = world.getChunkAt(chunkcoordintpair.x, chunkcoordintpair.z);
                    // Check if chunk is ready
                    if (chunk.isReady()) {
                        // Load nearby chunks
                        boolean waitLoad = false;
                        if (!checkAndLoadChunk(worldServer, chunkcoordintpair.x - 1, chunkcoordintpair.z)) {
                            waitLoad = true;
                        }
                        if (!checkAndLoadChunk(worldServer, chunkcoordintpair.x + 1, chunkcoordintpair.z)) {
                            waitLoad = true;
                        }
                        if (!checkAndLoadChunk(worldServer, chunkcoordintpair.x, chunkcoordintpair.z - 1)) {
                            waitLoad = true;
                        }
                        if (!checkAndLoadChunk(worldServer, chunkcoordintpair.x, chunkcoordintpair.z + 1)) {
                            waitLoad = true;
                        }
                        if (!waitLoad) {
                            // Queue the chunk for processing
                            processingQueue.add(chunkcoordintpair);
                            // Add the chunk to the list to create a packet
                            chunks.add(chunk);
                            internalQueue.remove(i);
                            continue;
                        }
                    }
                }
                i++;
            }

            // If there are chunks to process
            if (!chunks.isEmpty()) {

                // Create the actual packet
                lastPacket = new PacketPlayOutMapChunkBulk(chunks);
                // Put into wrapper
                // Send to Processing Thread
                PacketChunkDataBulk packet = new PacketChunkDataBulk();
                try {
                    packet.readPacket(lastPacket);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                ChunkProcessingThread.Queue(packet, player, this);
            }
        }
    }

    private void updateTileEntity(Set<MinecraftBlock> signs, TileEntity tileentity) {
        if (tileentity != null) {
            if (tileentity instanceof TileEntitySign) {
                BlockPosition position = tileentity.getPosition();
                if (signs != null && signs.contains(new MinecraftBlock(position.getX(), position.getY(), position.getZ()))) {
                    return;
                }
            }
            Packet packet = tileentity.getUpdatePacket();

            if (packet != null) {
                player.getHandle().playerConnection.sendPacket(packet);
            }
        }
    }

    private boolean checkAndLoadChunk(WorldServer worldServer, int x, int z) {
        if (!worldServer.N().isChunkLoaded(x, z)) {
            worldServer.chunkProviderServer.getChunkAt(x, z);
            return false;
        }
        return true;
    }

    @Override
    public ListIterator<ChunkCoordIntPair> listIterator() {
        return new FakeIterator();
    }

    private class FakeIterator implements ListIterator<ChunkCoordIntPair> {

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public ChunkCoordIntPair next() {
            return null;
        }

        @Override
        public boolean hasPrevious() {
            return false;
        }

        @Override
        public ChunkCoordIntPair previous() {
            return null;
        }

        @Override
        public int nextIndex() {
            return 0;
        }

        @Override
        public int previousIndex() {
            return 0;
        }

        @Override
        public void remove() {
        }

        @Override
        public void set(ChunkCoordIntPair e) {
        }

        @Override
        public void add(ChunkCoordIntPair e) {
        }
    }

    private static class ChunkCoordComparator implements java.util.Comparator<ChunkCoordIntPair> {
        private int x;
        private int z;

        public ChunkCoordComparator(EntityPlayer entityplayer) {
            x = (int) entityplayer.locX >> 4;
            z = (int) entityplayer.locZ >> 4;
        }

        public int compare(ChunkCoordIntPair a, ChunkCoordIntPair b) {
            if (a.equals(b)) {
                return 0;
            }

            // Subtract current position to set center point
            int ax = a.x - this.x;
            int az = a.z - this.z;
            int bx = b.x - this.x;
            int bz = b.z - this.z;

            int result = ((ax - bx) * (ax + bx)) + ((az - bz) * (az + bz));
            if (result != 0) {
                return result;
            }

            if (ax < 0) {
                if (bx < 0) {
                    return bz - az;
                } else {
                    return -1;
                }
            } else {
                if (bx < 0) {
                    return 1;
                } else {
                    return az - bz;
                }
            }
        }
    }
}

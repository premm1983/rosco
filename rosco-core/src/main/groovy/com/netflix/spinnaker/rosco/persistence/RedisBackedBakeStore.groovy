/*
 * Copyright 2015 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.rosco.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.rosco.api.Bake
import com.netflix.spinnaker.rosco.api.BakeRequest
import com.netflix.spinnaker.rosco.api.BakeStatus
import org.springframework.beans.factory.annotation.Autowired
import redis.clients.jedis.JedisPool

class RedisBackedBakeStore implements BakeStore {

  @Autowired
  ObjectMapper mapper

  private JedisPool jedisPool;

  public RedisBackedBakeStore(JedisPool jedisPool) {
    this.jedisPool = jedisPool;
  }

  @Override
  public boolean acquireBakeLock(String bakeKey) {
    def lockKey = "lock:$bakeKey"
    def ttlMilliseconds = "5000"
    def acquireBakeLockScript = """\
        -- Set the $lockKey key if it's not already set.
        if redis.call('SETNX', KEYS[1], 'locked') == 1 then
          -- Set TTL of 5 seconds.
          redis.call('PEXPIRE', KEYS[1], ARGV[1])

          -- Delete the $bakeKey key.
          redis.call('DEL', KEYS[2])

          -- We acquired the lock.
          return true
        else
          -- We failed to acquire the lock.
          return false
        end
        """
    def keyList = [lockKey.toString(), bakeKey]
    def argList = [ttlMilliseconds]
    def jedis = jedisPool.getResource()

    jedis.withCloseable {
      return jedis.eval(acquireBakeLockScript, keyList, argList)
    }
  }

  @Override
  public BakeStatus storeNewBakeStatus(String bakeKey, String region, BakeRequest bakeRequest, String bakeId) {
    def lockKey = "lock:$bakeKey"
    def bakeRequestJson = mapper.writeValueAsString(bakeRequest)
    def bakeStatus = new BakeStatus(id: bakeId, resource_id: bakeId, state: BakeStatus.State.PENDING)
    def bakeStatusJson = mapper.writeValueAsString(bakeStatus)
    def creationTimestamp = System.currentTimeMillis()
    def storeNewBakeStatusScript = """\
        -- Delete the $bakeId key.
        redis.call('DEL', KEYS[2])

        -- Add $bakeKey to set of bakes.
        redis.call('ZADD', KEYS[1], ARGV[1], KEYS[3])

        -- If we lost a race to initiate a new bake, just return the race winner's bake status.
        -- TODO(duftler): When rush supports cancelling an in-flight script execution, employ that here.
        if redis.call('EXISTS', KEYS[3]) == 1 then
          return redis.call('HMGET', KEYS[3], 'bakeStatus')
        end

        -- Set $bakeId hash values.
        redis.call('HMSET', KEYS[2],
                   'bakeKey', KEYS[3],
                   'region', ARGV[2],
                   'bakeRequest', ARGV[3],
                   'bakeStatus', ARGV[4],
                   'creationTimestamp', ARGV[1])

        -- Set $bakeKey hash values.
        redis.call('HMSET', KEYS[3],
                   'id', KEYS[2],
                   'region', ARGV[2],
                   'bakeRequest', ARGV[3],
                   'bakeStatus', ARGV[4],
                   'creationTimestamp', ARGV[1])

        -- Add $bakeId to set of incomplete bakes.
        redis.call('SADD', KEYS[4], KEYS[2])

        -- Delete the $lockKey key instead of just allowing it to wait out the TTL.
        redis.call('DEL', KEYS[5])
"""
    def keyList = ["allBakes", bakeId, bakeKey, "allBakes:incomplete", lockKey.toString()]
    def argList = [creationTimestamp.toString(), region, bakeRequestJson, bakeStatusJson]
    def jedis = jedisPool.getResource()

    jedis.withCloseable {
      def result = jedis.eval(storeNewBakeStatusScript, keyList, argList)

      // Check if the script returned a bake status set by the winner of a race.
      if (result?.getAt(0)) {
        bakeStatus = mapper.readValue(result[0], BakeStatus)
      }
    }

    return bakeStatus
  }

  @Override
  public void updateBakeDetails(Bake bakeDetails) {
    def bakeDetailsJson = mapper.writeValueAsString(bakeDetails)
    def updateBakeDetailsScript = """\
        -- Retrieve the bake key associated with $bakeDetails.id.
        local bake_key = redis.call('HGET', KEYS[1], 'bakeKey')

        -- Update the bake details set on the $bakeDetails.id hash.
        redis.call('HSET', KEYS[1], 'bakeDetails', ARGV[1])

        -- Update the bake details set on the bake key hash.
        redis.call('HSET', bake_key, 'bakeDetails', ARGV[1])
        """
    def keyList = [bakeDetails.id]
    def argList = [bakeDetailsJson]
    def jedis = jedisPool.getResource()

    jedis.withCloseable {
      jedis.eval(updateBakeDetailsScript, keyList, argList)
    }
  }

  @Override
  public void updateBakeStatus(BakeStatus bakeStatus, Map<String, String> logsContent=[:]) {
    def bakeStatusJson = mapper.writeValueAsString(bakeStatus)
    def bakeLogsJson = mapper.writeValueAsString(logsContent ?: [:])

    def updateBakeStatusScript = """\
        -- Retrieve the bake key associated with $bakeStatus.id.
        local bake_key = redis.call('HGET', KEYS[1], 'bakeKey')

        -- Update the bake status and logs set on the $bakeStatus.id hash.
        redis.call('HMSET', KEYS[1],
                   'bakeStatus', ARGV[1],
                   'bakeLogs', ARGV[2])

        if bake_key then
          -- Update the bake status and logs set on the bake key hash.
          redis.call('HMSET', bake_key,
                     'bakeStatus', ARGV[1],
                     'bakeLogs', ARGV[2])
        end
        """

    if (bakeStatus.state != BakeStatus.State.PENDING && bakeStatus.state != BakeStatus.State.RUNNING) {
      updateBakeStatusScript += """\
          -- Remove $bakeStatus.id from set of incomplete bakes.
          redis.call('SREM', KEYS[2], KEYS[1])
          """
    }

    def keyList = [bakeStatus.id, "allBakes:incomplete"]
    def argList = [bakeStatusJson, bakeLogsJson]
    def jedis = jedisPool.getResource()

    jedis.withCloseable {
      jedis.eval(updateBakeStatusScript, keyList, argList)
    }
  }

  @Override
  public void storeBakeError(String bakeId, String error) {
    def storeBakeErrorScript = """\
        -- Retrieve the bake key associated with $bakeId.
        local bake_key = redis.call('HGET', KEYS[1], 'bakeKey')

        -- Update the error set on the $bakeId hash.
        redis.call('HSET', KEYS[1], 'bakeError', ARGV[1])

        if bake_key then
          -- Update the error set on the bake key hash.
          redis.call('HSET', bake_key, 'bakeError', ARGV[1])
        end
        """
    def keyList = [bakeId]
    def argList = [error]
    def jedis = jedisPool.getResource()

    jedis.withCloseable {
      jedis.eval(storeBakeErrorScript, keyList, argList)
    }
  }

  @Override
  public String retrieveRegionById(String bakeId) {
    def jedis = jedisPool.getResource()

    jedis.withCloseable {
      return jedis.hget(bakeId, "region")
    }
  }

  @Override
  public BakeStatus retrieveBakeStatusByKey(String bakeKey) {
    def jedis = jedisPool.getResource()

    jedis.withCloseable {
      def bakeStatusJson = jedis.hget(bakeKey, "bakeStatus")

      return bakeStatusJson ? mapper.readValue(bakeStatusJson, BakeStatus) : null
    }
  }

  @Override
  public BakeStatus retrieveBakeStatusById(String bakeId) {
    def jedis = jedisPool.getResource()

    jedis.withCloseable {
      def bakeStatusJson = jedis.hget(bakeId, "bakeStatus")

      return bakeStatusJson ? mapper.readValue(bakeStatusJson, BakeStatus) : null
    }
  }

  @Override
  public Bake retrieveBakeDetailsById(String bakeId) {
    def jedis = jedisPool.getResource()

    jedis.withCloseable {
      def bakeDetailsJson = jedis.hget(bakeId, "bakeDetails")

      return bakeDetailsJson ? mapper.readValue(bakeDetailsJson, Bake) : null
    }
  }

  @Override
  public Map<String, String> retrieveBakeLogsById(String bakeId) {
    def jedis = jedisPool.getResource()

    jedis.withCloseable {
      def bakeLogsJson = jedis.hget(bakeId, "bakeLogs")

      return bakeLogsJson ? mapper.readValue(bakeLogsJson, Map) : null
    }
  }

  @Override
  public boolean deleteBakeByKey(String bakeKey) {
    def deleteBakeByKeyScript = """\
        -- Retrieve the bake id associated with $bakeKey.
        local bake_id = redis.call('HGET', KEYS[1], 'id')

        -- Remove $bakeKey from the set of bakes.
        redis.call('ZREM', KEYS[2], KEYS[1])

        -- Delete the $bakeKey key.
        local ret = redis.call('DEL', KEYS[1])

        if bake_id then
          -- Remove the bake id from the set of incomplete bakes.
          redis.call('SREM', KEYS[3], bake_id)

          -- Delete the bake id key.
          redis.call('DEL', bake_id)
        end

        return ret
        """
    def keyList = [bakeKey, "allBakes", "allBakes:incomplete"]
    def jedis = jedisPool.getResource()

    jedis.withCloseable {
      return jedis.eval(deleteBakeByKeyScript, keyList, []) == 1
    }
  }

  @Override
  public boolean cancelBakeById(String bakeId) {
    def bakeStatus = new BakeStatus(id: bakeId,
                                    resource_id: bakeId,
                                    state: BakeStatus.State.CANCELLED,
                                    result: BakeStatus.Result.FAILURE)
    def bakeStatusJson = mapper.writeValueAsString(bakeStatus)
    def cancelBakeByIdScript = """\
        -- Retrieve the bake key associated with $bakeId.
        local bake_key = redis.call('HGET', KEYS[1], 'bakeKey')

        -- Remove $bakeId from the set of incomplete bakes.
        local ret = redis.call('SREM', KEYS[2], KEYS[1])

        -- Update the bake status set on the $bakeId hash.
        redis.call('HSET', KEYS[1], 'bakeStatus', ARGV[1])

        if bake_key then
          -- Remove the bake key from the set of bakes.
          redis.call('ZREM', KEYS[3], bake_key)

          -- Delete the bake key key.
          redis.call('DEL', bake_key)
        end

        return ret
        """
    def keyList = [bakeId, "allBakes:incomplete", "allBakes"]
    def argList = [bakeStatusJson]
    def jedis = jedisPool.getResource()

    jedis.withCloseable {
      return jedis.eval(cancelBakeByIdScript, keyList, argList) == 1
    }
  }

  @Override
  public Set<String> getIncompleteBakeIds() {
    def jedis = jedisPool.getResource()

    jedis.withCloseable {
      return jedis.smembers("allBakes:incomplete")
    }
  }

}

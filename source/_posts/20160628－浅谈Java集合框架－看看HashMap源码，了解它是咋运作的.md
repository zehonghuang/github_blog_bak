---
title: 浅谈Java集合框架－看看HashMap源码，了解它是咋运作的?
date: 2016-06-28 14:33:49
categories:
- 集合框架
tags:
- java
- hashmap
---

初探HashMap源码，显然要理解它的运作并不难，只要基本掌握哈希桶这种数据结构。本文只在源码上对`get(K key)`和`put(K key, V value)`进行解读，并了解HashMap的原理。我看的是jdk1.7的源码！

---

``` java
public HashMap(int initialCapacity, float loadFactor) {
  this.loadFactor = loadFactor;
  threshold = initialCapacity;

  //在HashMap类中无用
  init();
}

//初始化哈希槽
private void inflateTable(int toSize) {
  //threshold只是作为标准值，下面求一个略大于标准值的容量
  int capacity = roundUpToPowerOf2(toSize);
  //负载因子折算threshold
  threshold = (int) Math.min(capacity * loadFactor, MAXIMUM_CAPACITY + 1);
  //注意Entry本身是链表结构（即桶），table只是桶的列表
  table = new Entry[capacity];
  //初始化hashseed
  initHashSeedAsNeeded(capacity);
}

private static int roundUpToPowerOf2(int number) {
  // assert number >= 0 : "number must be non-negative";
  return number >= MAXIMUM_CAPACITY
    ? MAXIMUM_CAPACITY
      //number-1两倍，取最高位1的值
      : (number > 1) ? Integer.highestOneBit((number - 1) << 1) : 1;
}

public V put(K key, V value) {
  if (table == EMPTY_TABLE) {
    //
    inflateTable(threshold);
  }
  //key为null时，hash为0，即table[0]
  if (key == null)
    //该方法的代码段与下面一致，i＝0
    return putForNullKey(value);
  int hash = hash(key);
  //计算下标
  int i = indexFor(hash, table.length);
  //获取对于hash的桶，e!=null则下一个entry
  for (Entry<K,V> e = table[i]; e != null; e = e.next) {
    Object k;
    //比对key值，若key存在则体会value，并返回旧value
    if (e.hash == hash && ((k = e.key) == key || key.equals(k))) {
      V oldValue = e.value;
      e.value = value;
      //貌似毫无用处
      e.recordAccess(this);
      return oldValue;
    }
  }

  modCount++;
  //key值不存在则插入
  addEntry(hash, key, value, i);
  return null;
}

void addEntry(int hash, K key, V value, int bucketIndex) {
  //entry数满标准值，且该槽点不为null。为什么这样判断？因为大于threshold，不代表bucket已经用完，size只是entry的数量
  if ((size >= threshold) && (null != table[bucketIndex])) {
    //重设标准值，扩容
    resize(2 * table.length);
    //重新计算hash
    hash = (null != key) ? hash(key) : 0;
    //重新计算下表
    bucketIndex = indexFor(hash, table.length);
  }

  createEntry(hash, key, value, bucketIndex);
}

void createEntry(int hash, K key, V value, int bucketIndex) {
  Entry<K,V> e = table[bucketIndex];
  //把新entry插入链表头
  table[bucketIndex] = new Entry<>(hash, key, value, e);
  size++;
}

public V get(Object key) {
  if (key == null)
    return getForNullKey();
  Entry<K,V> entry = getEntry(key);

  return null == entry ? null : entry.getValue();
}

private V getForNullKey() {
  if (size == 0) {
    return null;
  }
  //前面说过key为null时，hash为0
  for (Entry<K,V> e = table[0]; e != null; e = e.next) {
    if (e.key == null)
      return e.value;
  }
  return null;
}

final Entry<K,V> getEntry(Object key) {
  if (size == 0) {
    return null;
  }

  int hash = (key == null) ? 0 : hash(key);
  //获取hash对应的bucket，遍历Entry
  for (Entry<K,V> e = table[indexFor(hash, table.length)]; e != null; e = e.next) {
      Object k;
      if (e.hash == hash && ((k = e.key) == key || (key != null && key.equals(k))))
          return e;
  }
  return null;
}

void resize(int newCapacity) {

  Entry[] oldTable = table;
  int oldCapacity = oldTable.length;
  if (oldCapacity == MAXIMUM_CAPACITY) {
    threshold = Integer.MAX_VALUE;
    return;
  }

  Entry[] newTable = new Entry[newCapacity];
  transfer(newTable, initHashSeedAsNeeded(newCapacity));
  table = newTable;
  threshold = (int)Math.min(newCapacity * loadFactor, MAXIMUM_CAPACITY + 1);
}
```

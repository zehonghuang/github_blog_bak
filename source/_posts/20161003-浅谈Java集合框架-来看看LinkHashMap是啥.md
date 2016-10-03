---
title: 浅谈Java集合框架-来看看LinkHashMap是啥!
date: 2016-10-03 01:55:32
categories:
- 集合框架
tags:
- java
- LinkHashMap
---

跳槽后，就没怎么看jdk，趁着十一看回点集合框架。我们知道HashMap是无序的，jdk也给我们提供了算是有序的HashMap，即LinkedHashMap。然而它只保留了操作的相对有序，而非TreeMap的Key自然有序。

---

LinkedHashMap显然继承了HashMap的绝大部分特性，新Entry则添加了`before`和`after`两个指针，以及维护Entry链表的方法。需要说明的是，HashMap.Entry的单链表无关，那只是用于解决hash冲突而已。

``` java
/**
 * LinkedHashMap entry.
 */
private static class Entry<K,V> extends HashMap.Entry<K,V> {
  //提供了双链表的指针
  Entry<K,V> before, after;

  Entry(int hash, K key, V value, HashMap.Entry<K,V> next) {
    super(hash, key, value, next);
  }

  //删除元素，改变头尾指针
  private void remove() {
      before.after = after;
      after.before = before;
  }

  //链表头追加元素
  private void addBefore(Entry<K,V> existingEntry) {
      after  = existingEntry;
      before = existingEntry.before;
      before.after = this;
      after.before = this;
  }

  /**
   * 记录访问，即使将最新一次访问放在链表头部
   */
  void recordAccess(HashMap<K,V> m) {
      LinkedHashMap<K,V> lm = (LinkedHashMap<K,V>)m;
      //构造方法能够设置该值
      if (lm.accessOrder) {
          lm.modCount++;
          remove();
          addBefore(lm.header);
      }
  }

  void recordRemoval(HashMap<K,V> m) {
      remove();
  }
}

//重写了get方法
public V get(Object key) {
  Entry<K,V> e = (Entry<K,V>)getEntry(key);
  if (e == null)
      return null;
  //访问记录
  e.recordAccess(this);
  return e.value;
}

void addEntry(int hash, K key, V value, int bucketIndex) {
  super.addEntry(hash, key, value, bucketIndex);

  //移除最近且使用次数最少的元素
  Entry<K,V> eldest = header.after;
  if (removeEldestEntry(eldest)) {
      removeEntryForKey(eldest.key);
  }
}

//多了将Entry添加到双链表头
void createEntry(int hash, K key, V value, int bucketIndex) {
  HashMap.Entry<K,V> old = table[bucketIndex];
  Entry<K,V> e = new Entry<>(hash, key, value, old);
  table[bucketIndex] = e;
  //在此
  e.addBefore(header);
  size++;
}

//重写该方法，可以实现lru，可以参考ehcache的SpoolingLinkedHashMap
protected boolean removeEldestEntry(Map.Entry<K,V> eldest) {
  return false;
}
```

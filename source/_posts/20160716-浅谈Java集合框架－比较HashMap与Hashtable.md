---
title: 浅谈Java集合框架－比较HashMap与Hashtable
date: 2016-07-16 21:32:29
categories:
- 集合框架
tags:
- java
- Hashtable
- HashMap
---

Hashtable实际上就是一个线程安全的HashMap，不过它是个遗留下来的过气类，其性能也并比不少ConcurrentHashMap。

---

``` java
//HashMap默认长度是1<<4 aka 16
public Hashtable() {
  this(11, 0.75f);
}
//很明显，HashMap没有synchronized，并不线程安全。
public synchronized V put(K key, V value) {
  // Make sure the value is not null
  if (value == null) {
    //HashMap的key允许一个null
    //HashMap源码：return putForNullKey(value);
    throw new NullPointerException();
  }

  // Makes sure the key is not already in the hashtable.
  Entry tab[] = table;
  int hash = hash(key);
  int index = (hash & 0x7FFFFFFF) % tab.length;
  for (Entry<K,V> e = tab[index] ; e != null ; e = e.next) {
    if ((e.hash == hash) && e.key.equals(key)) {
      V old = e.value;
      e.value = value;
      return old;
    }
  }

  modCount++;
  if (count >= threshold) {
    // Rehash the table if the threshold is exceeded
    rehash();
    tab = table;
    hash = hash(key);
    index = (hash & 0x7FFFFFFF) % tab.length;
  }

  // Creates the new entry.
  Entry<K,V> e = tab[index];
  tab[index] = new Entry<>(hash, key, value, e);
  count++;
  return null;
}

public synchronized V get(Object key) {
  //if (key == null)
  //  return getForNullKey();HashMap的key有null值
  Entry tab[] = table;
  int hash = hash(key);
  int index = (hash & 0x7FFFFFFF) % tab.length;
  for (Entry<K,V> e = tab[index] ; e != null ; e = e.next) {
    if ((e.hash == hash) && e.key.equals(key)) {
      return e.value;
    }
  }
  return null;
}
```

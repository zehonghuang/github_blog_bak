---
title: 浅谈Java集合框架－瞧瞧LinkedArray源码，查询效率为何如此低下？
date: 2016-06-28 23:22:01
categories:
- 集合框架
tags:
- java
- LinkedArray
---

平时用惯ArrayList插入查询，无视性能而忽略了LinkedArray，今日也反省反省，研究了一下
LinkedArray和ArrayList源码。其实理解两者原理和区别并不难，都是简单数据结构的应用。

---

## LinkedArray源码

双链表的好处就是有前后指针，插入只需要更新指针指向，随机查询则需要遍历。

``` java
public boolean add(E e) {
  linkLast(e);
  return true;
}

//这个很好理解了，往列表尾端添加元素
void linkLast(E e) {
  //现最后一个元素
  final Node<E> l = last;
  //即将添加的元素
  final Node<E> newNode = new Node<>(l, e, null);
  //last指针向后移
  last = newNode;
  //没有尾元素
  if (l == null)
    first = newNode;
  else
    //原尾元素next指向新下一个元素
    l.next = newNode;
  size++;
  modCount++;
}

public void add(int index, E element) {
  //检查index是否越界，是则抛出运行时异常IndexOutOfBoundsException
  checkPositionIndex(index);
  //同add(E element)
  if (index == size)
    linkLast(element);
  else
    linkBefore(element, node(index));
}

void linkBefore(E e, Node<E> succ) {
  //succ元素的前任prev
  final Node<E> pred = succ.prev;
  //新元素成功插入succ与其前任prev中间
  final Node<E> newNode = new Node<>(pred, e, succ);
  //于是succ的现任prev是新元素e
  succ.prev = newNode;
  if (pred == null)
    first = newNode;
  else
    pred.next = newNode;
  size++;
  modCount++;
}

Node<E> node(int index) {
  // assert isElementIndex(index);
  //类似二分法遍历，没什么好说的，size >> 1 aka size/2
  if (index < (size >> 1)) {
    Node<E> x = first;
    for (int i = 0; i < index; i++)
      x = x.next;
    return x;
  } else {
    Node<E> x = last;
    for (int i = size - 1; i > index; i--)
      x = x.prev;
    return x;
  }
}

public E get(int index) {
  checkElementIndex(index);
  return node(index).item;
}
```

## ArrayList源码

往数组末尾插入元素当然快，但随机插入可就不一定了，需要将子数组往后移且或扩容（LinkedArray不
需要扩容）。

``` java
public boolean add(E e) {
  //主要功能是扩容
  ensureCapacityInternal(size + 1);
  elementData[size++] = e;
  return true;
}

private void ensureCapacityInternal(int minCapacity) {
  //就是说，即使你在ArrayList(int initialCapacity)传小于10的值，elementData最小长度依然是10
  if (elementData == EMPTY_ELEMENTDATA) {
    minCapacity = Math.max(DEFAULT_CAPACITY, minCapacity);
  }

  ensureExplicitCapacity(minCapacity);
}

private void ensureExplicitCapacity(int minCapacity) {
  modCount++;

  if (minCapacity - elementData.length > 0)
    grow(minCapacity);
}

//扩容！！！
private void grow(int minCapacity) {
  int oldCapacity = elementData.length;
  int newCapacity = oldCapacity + (oldCapacity >> 1);
  if (newCapacity - minCapacity < 0)
    newCapacity = minCapacity;
  if (newCapacity - MAX_ARRAY_SIZE > 0)
    newCapacity = hugeCapacity(minCapacity);
  elementData = Arrays.copyOf(elementData, newCapacity);
}

public void add(int index, E element) {
  rangeCheckForAdd(index);

  ensureCapacityInternal(size + 1);
  //这是ArrayList插入操作性能差的罪魁祸首
  System.arraycopy(elementData, index, elementData, index + 1,
    size - index);
  elementData[index] = element;
  size++;
}
```

## 稍微总结一下

``` java
//ArrayList需要随机插入时，可以转为new LinkedList(new ArrayList());
public LinkedList(Collection<? extends E> c) {
  this();
  addAll(c);
}

//LinkedList需要随机查询时，可以转为new ArrayList(new LinkedList());
public ArrayList(Collection<? extends E> c) {
  elementData = c.toArray();
  size = elementData.length;
  if (elementData.getClass() != Object[].class)
  elementData = Arrays.copyOf(elementData, size, Object[].class);
}
```

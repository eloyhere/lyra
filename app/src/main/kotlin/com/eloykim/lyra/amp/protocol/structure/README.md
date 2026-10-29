# structure — AMP 结构工具 Kotlin 移植

本包是 `R:\project\attention-manifest-protocol\src\structure\*.ts` 的忠实 Kotlin 移植。
TypeScript 是唯一事实来源；本文件记录文件映射、API 形状差异与**刻意保留**的契约细节。
测试位于 `src/test/kotlin/com/eloykim/lyra/amp/protocol/structure/`，逐条移植
`tools/*-check.ts`（见文末映射），并补了参考仓库没有独立检查的两块内容
（`hash.ts` 契约、`ordered/unordered` 容器契约）。

## 文件映射（21 个 TS 文件）

| TypeScript | Kotlin | 说明 |
| --- | --- | --- |
| `compare.ts` | `Compare.kt` | 比较器原语；`TypeError` → `IllegalArgumentException` |
| `hash.ts` | `Hash.kt` | 64 位内容哈希 / 键哈希、`KeyStrategy`、fail-fast 异常 |
| `filterKey.ts` | `FilterKey.kt` | `FilterKey` sealed interface + 字节编码 |
| `iterator.ts` | `Iterator.kt` | 快照 `Generator` → `Sequence` |
| `ordered.ts` | `Ordered.kt` | `OrderedSet` / `OrderedMap`（桶链 + 侵入式链表） |
| `unordered.ts` | `Unordered.kt` | `UnorderedSet` / `UnorderedMap`（2 的幂次桶、0.75 负载因子） |
| `stack.ts` | `Stack.kt` | 数组栈，自顶向下快照 |
| `queue.ts` | `Queue.kt` | 环形缓冲区队列（8 起步、翻倍/减半） |
| `deque.ts` | `Deque.kt` | 环形缓冲区双端队列 |
| `heap.ts` | `Heap.kt` | `BinaryHeap` + `HeapOrder` |
| `segmentTree.ts` | `SegmentTree.kt` | 迭代式结合律线段树 |
| `abstractSyntaxTree.ts` | `AbstractSyntaxTree.kt` | `AbstractSyntaxTree` / `AstNode` / `SourceSpan` / `AstOrder` |
| `binaryTree.ts` | `BinaryTree.kt` | `BinaryTree` / `BinaryTreeNode` / `BinaryTreeOrder` |
| `binarySearchTree.ts` | `BinarySearchTree.kt` | 不平衡 BST |
| `avlTree.ts` | `AvlTree.kt` | `AVLTree` |
| `redBlackTree.ts` | `RedBlackTree.kt` | `RedBlackTree`（CLRS 插入/删除修复） |
| `priority.ts` | `Priority.kt` | `PriorityQueue` / `IndexedPriorityQueue` / `BucketQueue` / `TopK` |
| `lru.ts` | `Lru.kt` | `LRUList` / `LRUListNode` / `LRUOrderedMap` / `LRUUnorderedMap` / 两个 Set |
| `bloomFilter.ts` | `BloomFilter.kt` | 位数组 + 双哈希探测 |
| `cuckooFilter.ts` | `CuckooFilter.kt` | 双桶 + 指纹 + 可回滚踢出链 |
| `index.ts` | 本文件 | 包级导出表；Kotlin 无 barrel 需求 |

## 刻意保留的契约

- `OrderedMap`/`OrderedSet`：查找平均 O(1)，插入顺序由侵入式双向链表维护；更新已有键
  不移动位置，删除后重加移到末尾；结构性修改使已创建的迭代器抛
  `ConcurrentModificationException`，更新已有键的值不算结构性修改；空容器 `clear()`
  不改变版本号。
- `Unordered*`：2 的幂次桶、`hash & (len-1)`、链头插入、负载因子 0.75 时翻倍并重挂
  全部节点；迭代顺序是桶顺序（未指定，扩容后可变）。
- `BinaryHeap`：BFS 数组序；`toArray()`/迭代暴露**堆序**；`order = MAX` 在内部反转
  比较器；空堆 `pop`/`peek`/`replace` 返回 `null`，`replace` 在空堆上等价于插入。
- `PriorityQueue`/`IndexedPriorityQueue`/`BucketQueue`/`TopK`：`toArray`、迭代、`drain`
  暴露**出队顺序**；等优先级按到达顺序（`IndexedPriorityQueue.set` 复用到达序号）；
  `TopK` 拒绝与当前最差值相等的值（稳定性）。
- `SegmentTree`：叶子按索引顺序合并，非交换运算（字符串拼接）结果正确；空区间返回
  单位元；`size = 0` 合法；`queryAll()` 对空树返回单位元。
- 搜索树：比较结果为 0 视为重复并拒绝；`min`/`max`/`floor`/`ceil` 按比较器顺序；
  `validate()` 返回违规字符串列表（空列表表示合法）。
- `LRU*`：`get`/已有键 `set`/`touch` 更新新鲜度，`has`/`peek` 不更新；超过容量按
  LRU 剔除；有序变体的迭代顺序仍是插入顺序（与新鲜度独立）。
- Bloom 无假阴性、Cuckoo 支持删除且失败插入完整回滚（不丢已接受的键）。

## 与 TypeScript API 的差异（均为 Kotlin 侧必要形状变化）

1. **选项对象 → 命名参数/默认参数**。例如 `BinaryHeap({compare, order, values})` →
   `BinaryHeap(compare = ..., order = ..., values = ...)`；`order` 使用
   `HeapOrder.MIN/MAX` 枚举（`HeapOrder.fromWire` 接受 `"min"/"max"`）。
   搜索树的 `TreeOptions<T>` 收敛为构造参数 `compare`。
2. **Generator → `Sequence`/`Iterable`**。`keys()/values()/entries()` 返回
   `Sequence`（懒、可重复迭代），快照方法返回不可变 `List`。fail-fast 版本在**请求
   迭代器时**捕获版本号，因此 `Sequence` 的每次迭代从迭代开始处生效，而不是
   TypeScript `Generator` 的“创建即固定”。语义差别仅在“先取序列、后修改、再迭代”的
   极端情况下出现。
3. **错误类型**。`TypeError`/`RangeError` → `IllegalArgumentException`；
   非法下标/区间 → `IndexOutOfBoundsException`（消息保留 TypeScript 文案）。
   迭代期间的并发修改 → `java.util.ConcurrentModificationException`。
4. **`equals`/`toJSON` 重命名**。`AbstractSyntaxTree.equals` → `contentEquals`（避免与
   Kotlin 引用等价 `Any.equals` 混淆）；`toJSON` → `toPlain`（Kotlin 节点本身就是普通
   对象，不存在“类实例 vs 普通对象”的差别）。
5. **哈希引用语义**。JavaScript 用 `WeakMap` 做对象身份哈希；Kotlin 对象具有
   `equals/hashCode` 值语义，因此默认 `hashKey` 对引用类型使用 `hashCode`，`sameKey`
   使用 SameValueZero（`-0.0 == +0.0`、`NaN == NaN`，其余用 `equals`）。这与
   Java/Kotlin `HashMap` 的契约一致（`equals` 为真 ⇒ `hash` 相等），比 JS 身份语义更
   符合 Kotlin 习惯。需要 JS 式身份哈希时用 `hashIdentity(value)` 并显式传入
   `KeyStrategy`。JS 的 Symbol/函数分支在 Kotlin 无对应物，省略；`bigint` 映射到
   `java.math.BigInteger`；`Date` 没有 Java 的“Invalid Date”状态，因此不做无效日期拒绝。
6. **可空值**。TypeScript 的 `undefined` 是合法存储值；Kotlin 用 `null` 表示，因此
   “缺失”与“存了 null”只能靠 `size`/`has`/`isEmpty` 区分（与 TypeScript 文档提示一致）。
   `PriorityQueue`/`Stack`/`Queue`/`Deque` 的 `dequeue`/`pop`/`peek` 返回 `T?`。
7. **数值宽度**。`BucketQueue.enqueue` 的优先级是 `Int`（Kotlin 类型系统已排除
   `1.5`/`NaN`/超安全整数，因此对应拒绝测试只能覆盖类型允许的范围）；
   `TopK`/`LRU*` 的容量是 `Int`。`BloomFilter.bitSize`/`maxBits` 是 `Long`
   （默认上限 `2^31` 位超出 `Int` 范围）；`CuckooFilter.capacity` 是 `Long`
   （`bucketCount * bucketSize` 可超过 `Int`），`bucketCount` 仍是 `Int`。
8. **`forEach`**。TypeScript 的三参数回调（`value,value2,set` / `value,key,map`）不
   单独复刻；Kotlin 容器实现 `Iterable`，使用标准 `forEach`（映射得到 `Pair`）。
   需要三参数形状的调用方可用 `entries().forEach`。
9. **`LruEntry` 公开**。它只是共享 LRU 基类的类型参数细节；为了让
   `LruMapBase` 的构造签名可表达，Kotlin 侧设为 public（文档标注为实现细节）。
10. **`IndexedPriorityQueue`** 构造参数顺序为 `keyOf`、`compare`、`order`；
    `keys()` 返回 `List<K>`（TypeScript 返回数组快照）。

## 测试映射

| 参考检查 | Kotlin 测试 |
| --- | --- |
| `tools/structure-compare-check.ts` | `StructureCompareCheckTest.kt`（另含 `Hash.kt` 契约与 RNG 参考序列） |
| `tools/linear-structure-check.ts` | `LinearStructureCheckTest.kt` |
| `tools/tree-structure-check.ts` | `TreeStructureCheckTest.kt` |
| `tools/priority-check.ts` | `PriorityCheckTest.kt` |
| `tools/filter-check.ts` | `FilterCheckTest.kt` |
| `tools/lru-check.ts` | `LruCheckTest.kt` |
| `tools/segment-ast-check.ts` | `SegmentAstCheckTest.kt` |
| （无独立检查） | `OrderedUnorderedCheckTest.kt` |

`RandomSource.kt` 复刻参考检查的 mulberry32/LCG 生成器；`StructureCompareCheckTest`
用 Node 参考实现算出的前 5 个值逐位校验，保证随机差分测试与 TypeScript 检查使用同一
条随机序列。测试全部使用 JUnit 4，不依赖 Android 运行时。

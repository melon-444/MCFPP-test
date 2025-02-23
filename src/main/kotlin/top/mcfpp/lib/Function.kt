package top.mcfpp.lib

import top.mcfpp.CompileSettings
import top.mcfpp.Project
import top.mcfpp.annotations.InsertCommand
import top.mcfpp.command.Command
import top.mcfpp.command.CommandList
import top.mcfpp.command.Commands
import top.mcfpp.lang.*
import top.mcfpp.util.StringHelper
import java.lang.NullPointerException
import java.lang.reflect.Method

/**
 * 一个minecraft中的命令函数。
 *
 * 在mcfpp中，一个命令函数可能是单独存在的，也有可能是一个类的成员。
 *
 * 在一般的数据包中，命令函数的调用通常只会是一个简单的`function xxx:xxx`
 * 这样的形式。这条命令本身的意义便确实是调用一个函数。然而我们需要注意的是，在mc中，
 * 一个命令函数并没有通常意义上的栈，换句话说，所有的变量都是全局变量，这显然是不符合
 * 一般的高级语言的规范的。在mcfpp中，我们通过`storage`的方法来模拟一个函数
 * 的栈。
 *
 * mcfpp栈的模拟参考了[https://www.mcbbs.net/thread-1393132-1-1.html](https://www.mcbbs.net/thread-1393132-1-1.html)
 * 的方法。在下面的描述中，也是摘抄于此文。
 *
 * c语言底层是如何实现“局部变量”的？我们以 c 语言为例，看看函数底层的堆栈实现过程是什么样的？请看下面这段代码：
 * ```c
 * int test() {
 *         int a = 1;// 位置1
 *         funA(a);
 *         // 位置5
 * }
 * int funA(int a) {// 位置2
 *         a = a + 1;
 *         funB(a);
 *         // 位置4
 * }
 * int funB(int a) {// 位置3
 *         a = a + 1;
 * }
 * ```
 *
 * 位置①：现在父函数还没调用 funA，堆栈情况是：<br></br>
 * low address {父函数栈帧 ...  }high address<br></br>
 * （执行 funA(?) ）<br></br>
 * 位置②：当父函数调用 funA 时，会从栈顶开一块新的空间来保存 funA 的栈帧，堆栈情况是：<br></br>
 * low address{ funA栈帧 父函数栈帧 ... } high address<br></br>
 * （执行 a = a + 1）<br></br>
 * （执行 funB(a) ）<br></br>
 * 位置③：当 funA 调用 funB 时，会从栈顶开一块新的空间来保存 funB 的栈帧，堆栈情况是：<br></br>
 * low address { funB栈帧 funA栈帧 父函数栈帧 ... } high address<br></br>
 * （执行 a = a + 2）<br></br>
 * 位置④：funB 调用结束，funB 的栈帧被销毁，程序回到 funA 继续执行，堆栈情况是：<br></br>
 * low address { funA栈帧 父函数栈帧 ... } high address<br></br>
 * 位置⑤：funA 调用结束，funA 的栈帧被销毁，程序回到 父函数 继续执行，堆栈情况是：<br></br>
 * low address { 父函数栈帧 ... } high address<br></br>
 * 我们会发现，funA 和 funB 使用的变量都叫 a，但它们的位置是不同的，此处当前函数只会在属于自己的栈帧的内存空间上
 * 操作，不同函数之间的变量之所以不会互相干扰，也是因为它们在栈中使用的位置不同，此 a 非彼 a
 *
 *
 *
 * mcf 如何模拟这样的堆栈？<br></br>
 * 方法：将 storage 视为栈，将记分板视为寄存器<br></br>
 * 与汇编语言不同的是，一旦我们这么想，我们就拥有无限的寄存器，且每个寄存器都可以是专用的，所以在下面的叙述中，
 * 如果说“变量”，指的是寄存器，也就是记分板里的值；只有说“变量内存空间”，才是指 storage 中的值；变量内存空间类似函数栈帧<br></br>
 * 我们可以使用 storage 的一个列表，它专门用来存放函数的变量内存空间<br></br>
 * 列表的大致模样： stack_frame [{funB变量内存空间}, {funA变量内存空间}, {父函数变量内存空间}]<br></br>
 * 每次我们要调用一个函数，只需要在 stack_frame 列表中前插一个 {}，然后压入参数<br></br>
 *
 * 思路有了，接下来就是命令了。虽然前面的思路看起来非常复杂，但是实际上转化为命令的时候就非常简单了。
 * ```
 * `#父函数为子函数创建变量内存空间
 * data modify storage mny:program stack_frame prepend value {}
 * #父函数处理子函数的参数，压栈
 * execute store result storage mny:program stack_frame[0].xxx int 1 run ...
 * #给子函数打电话（划去）调用子函数
 * function xxx:xxx
 * #父函数销毁子函数变量内存空间
 * data remove storage mny:program stack_frame[0]
 * #父函数恢复记分板值
 * xxx（命令略去）
 * ```
 *
 * 你可以在[top.mcfpp.lib.antlr.McfppExprVisitor]中的[top.mcfpp.lib.antlr.McfppExprVisitor.visitVar]方法中看到mcfpp是如何实现的。
 *
 * @see InternalFunction
 */
open class Function : Member, FieldContainer {

    /**
     * 函数的返回变量
     */
    var returnVar: Var? = null

    /**
     * 包含所有命令的列表
     */
    var commands: CommandList

    /**
     * 函数的名字
     */
    val identifier: String

    /**
     * 函数的标签
     */
    val tags: ArrayList<FunctionTag> = ArrayList()

    /**
     * 函数的命名空间。默认为工程文件的明明空间
     */
    val namespace: String

    /**
     * 参数列表
     */
    var params: ArrayList<FunctionParam>

    /**
     * 函数编译时的缓存
     */
    var field: FunctionField

    /**
     * 这个函数调用的函数
     */
    val child: ArrayList<Function> = ArrayList()

    /**
     * 调用这个函数的函数
     */
    val parent: ArrayList<Function> = ArrayList()

    /**
     * 函数是否已经实际中止。用于break和continue语句。
     */
    var isEnd = false

    /**
     * 是否是抽象函数
     */
    var isAbstract = false

    /**
     * 函数的返回类型
     */
    val returnType : String

    /**
     * 函数是否有返回语句
     */
    var hasReturnStatement : Boolean = false

    /**
     * 访问修饰符。默认为private
     */
    override var accessModifier: Member.AccessModifier = Member.AccessModifier.PRIVATE

    /**
     * 是否是静态的。默认为否
     */
    override var isStatic : Boolean

    /**
     * 所在的复合类型（类/结构体/基本类型）。如果不是成员，则为null
     */
    var owner : CompoundData? = null

    /**
     * 在什么东西里面
     */
    var ownerType : OwnerType = OwnerType.NONE

    open val namespaceID: String
        /**
         * 获取这个函数的命名空间id，即xxx:xxx形式。可以用于命令
         * @return 函数的命名空间id
         */
        get() {
            val re: StringBuilder = if(ownerType == OwnerType.NONE){
                StringBuilder("$namespace:$identifier")
            }else{
                if(isStatic){
                    StringBuilder("$namespace:${owner!!.identifier}/static/$identifier")
                }else{
                    StringBuilder("$namespace:${owner!!.identifier}/$identifier")
                }
            }
            for (p in params) {
                re.append("_").append(p.type)
            }
            return StringHelper.toLowerCase(re.toString())
        }

    /**
     * 获取这个函数的不带有命名空间的id。仍然包含了参数信息
     */
    open val nameWithNamespace: String
        get() {
            val re: StringBuilder = if(ownerType == OwnerType.NONE){
                StringBuilder(identifier)
            }else{
                if(isStatic){
                    StringBuilder("${owner!!.identifier}/static/$identifier")
                }else{
                    StringBuilder("${owner!!.identifier}/$identifier")
                }
            }
            for (p in params) {
                re.append("_").append(p.type)
            }
            return StringHelper.toLowerCase(re.toString())
        }

    /**
     * 这个函数是否是入口函数。入口函数就是没有其他函数调用的函数，会额外在函数的开头结尾进行入栈和出栈的操作。
     */
    val isEntrance: Boolean
        get() {
            for (tag in tags){
                if(tags.equals(FunctionTag.TICK) || tags.equals(FunctionTag.LOAD)){
                    return true
                }
            }
            return false
        }

    /**
     * 函数含有的所有的命令。一个命令一行
     */
    val cmdStr: String
        get() {
            val qwq: StringBuilder = StringBuilder()
            for (s in commands) {
                qwq.append(s).append("\n")
            }
            return qwq.toString()
        }

    /**
     * 函数会给它的域中的变量的minecraft标识符加上的前缀。
     */
    @get:Override
    override val prefix: String
        get() = Project.currNamespace + "_func_" + identifier + "_"

    /**
     * 创建一个函数
     * @param identifier 函数的标识符
     */
    constructor(identifier: String, returnType: String = "void"):this(identifier, Project.currNamespace, returnType)

    /**
     * 创建一个全局函数，它有指定的命名空间
     * @param identifier 函数的标识符
     * @param namespace 函数的命名空间
     */
    constructor(identifier: String, namespace: String, returnType: String = "void"){
        this.identifier = identifier
        commands = CommandList()
        params = ArrayList()
        field = FunctionField(null, this)
        isStatic = false
        ownerType = OwnerType.NONE
        this.namespace = namespace
        this.returnType = returnType
        this.returnVar = buildReturnVar(returnType)
    }

    /**
     * 创建一个函数，并指定它所属的类。
     * @param identifier 函数的标识符
     */
    constructor(identifier: String, cls: Class, isStatic: Boolean, returnType: String = "void") {
        this.identifier = identifier
        commands = CommandList()
        params = ArrayList()
        namespace = cls.namespace
        ownerType = OwnerType.CLASS
        owner = cls
        this.isStatic = isStatic
        field = if (isStatic) {
            FunctionField(cls.field, this)
        } else {
            FunctionField(cls.staticField, this)
        }
        this.returnType = returnType
        this.returnVar = buildReturnVar(returnType)
    }

    /**
     * 创建一个函数，并指定它所属的接口。接口的函数总是抽象并且公开的
     * @param identifier 函数的标识符
     */
    constructor(identifier: String, itf: Interface, returnType: String = "void") {
        this.identifier = identifier
        commands = CommandList()
        params = ArrayList()
        namespace = itf.namespace
        ownerType = OwnerType.CLASS
        owner = itf
        this.isStatic = false
        field = FunctionField(null,null)
        this.returnType = returnType
        this.returnVar = buildReturnVar(returnType)
        this.isAbstract = true
        this.accessModifier = Member.AccessModifier.PUBLIC
    }

    /**
     * 创建一个函数，并指定它所属的结构体。
     * @param name 函数的标识符
     */
    constructor(name: String, struct: Struct, isStatic: Boolean, returnType: String = "void") {
        this.identifier = name
        commands = CommandList()
        params = ArrayList()
        namespace = struct.namespace
        ownerType = OwnerType.STRUCT
        owner = struct
        this.isStatic = isStatic
        field = if (isStatic) {
            FunctionField(struct.field, this)
        } else {
            FunctionField(struct.staticField, this)
        }
        this.returnType = returnType
        this.returnVar = buildReturnVar(returnType)
    }
    /**
     * 获取这个函数的id，它包含了这个函数的路径和函数的标识符。每一个函数的id都是唯一的
     * @return 函数id
     */
    fun getID(): String {
        return identifier
    }

    /**
     * 向这个函数对象添加一个函数标签。如果已经存在这个标签，则不会添加。
     *
     * @param tag 要添加的标签
     * @return 返回添加了标签以后的函数对象
     */
    fun addTag(tag : FunctionTag):Function{
        if(!tags.contains(tag)){
            tags.add(tag)
        }
        return this
    }

    /**
     * 构造函数的返回值
     *
     * @param returnType
     */
    private fun buildReturnVar(returnType: String): Var{
        return if(returnType == "void") Void()
        else Var.build("return",returnType,this)
    }

    /**
     * 写入这个函数的形参信息，同时为这个函数准备好包含形参的缓存
     *
     * @param ctx
     */
    open fun addParams(ctx: mcfppParser.ParameterListContext?) {
        //函数参数解析
        //如果是非静态成员方法
        //构造名为this的变量
        //如果是ClassType则不必构造。因此需要构造的变量一定是ClassPointer
        //由于静态的判断是在函数构造后进行的，此处无法进行isStatic判断。届时判断静态的时候去除第一个元素即可。
        if(ownerType != OwnerType.NONE && !isStatic){
            val thisObj = Var.build("this", owner!!.identifier, this)
            field.putVar("this",thisObj)
        }
        if(ctx == null) return
        for (param in ctx.parameter()) {
            val param1 = FunctionParam(
                param.type().text,
                param.Identifier().text,
                param.STATIC() != null
            )
            params.add(param1)
            //向函数缓存中写入变量
            when(param1.type){
                "int" -> {
                    field.putVar(param1.identifier, MCInt(this,"_param_" + param1.identifier))
                }
                "bool" -> {
                    field.putVar(param1.identifier, MCBool(this, "_param_" + param1.identifier))
                }
                else -> {
                    //引用类型
                    val q = param1.type.split(":")
                    val cls : Class?  = if(q.size == 1){
                        GlobalField.getClass(null, param1.type)
                    }else{
                        GlobalField.getClass(q[0],q[1])
                    }
                    if(cls == null){
                        Project.error("Undefined class:" + param1.type)
                    }else{
                        field.putVar(param1.identifier, ClassPointer(cls, param1.identifier))
                    }
                }
            }
        }
    }

    /**
     * 这个函数的形参类型
     */
    val paramTypeList: ArrayList<String>
        get() {
            val re: ArrayList<String> = ArrayList()
            for (p in params) {
                re.add(p.type)
            }
            return re
        }

    open fun invoke(args: ArrayList<Var>, caller: CanSelectMember?){
        when(caller){
            is CompoundDataType -> invoke(args, callerClassP = null)
            null -> invoke(args, callerClassP = null)
            is ClassBase -> invoke(args, callerClassP = caller)
            is StructBase -> invoke(args, caller)
            is Var -> invoke(args, caller)
        }
    }

    /**
     * 调用一个变量的某个成员函数
     *
     * @param args
     * @param caller
     */
    open fun invoke(args: ArrayList<Var>, caller: Var){
        //基本类型
        addCommand("#[Function ${this.namespaceID}] Function Pushing and argument passing")
        //给函数开栈
        addCommand("data modify storage mcfpp:system ${Project.defaultNamespace}.stack_frame prepend value {}")
        //传入this参数
        field.putVar("this",caller,true)
        //参数传递
        argPass(args)
        addCommand("function " + this.namespaceID)
        //static参数传回
        staticArgRef(args)
        //销毁指针，释放堆内存
        for (p in field.allVars){
            if (p is ClassPointer){
                p.dispose()
            }
        }
        //调用完毕，将子函数的栈销毁
        addCommand("data remove storage mcfpp:system " + Project.defaultNamespace + ".stack_frame[0]")
        //取出栈内的值
        fieldRestore()
    }

    /**
     * 调用这个函数。
     *
     * @param args 函数的参数
     * @param callerClassP 调用函数的实例
     * @see top.mcfpp.lib.antlr.McfppExprVisitor.visitVar
     */
    @InsertCommand
    open fun invoke(args: ArrayList<Var>, callerClassP: ClassBase?) {
        //给函数开栈
        addCommand("data modify storage mcfpp:system ${Project.defaultNamespace}.stack_frame prepend value {}")
        //参数传递
        argPass(args)
        //函数调用的命令
        when(callerClassP){
            is ClassPointer -> {
                val qwq = Commands.selectRun(callerClassP)
                addCommand(qwq[0])
                addCommand(qwq[1].build("function mcfpp.dynamic:function with entity @s data.functions.$identifier"))
            }
            is ClassObject -> {
                val qwq = Commands.selectRun(callerClassP)
                addCommand(qwq[0])
                addCommand(qwq[1].build("function mcfpp.dynamic:function with entity @s data.functions.$identifier"))
            }
            null -> {
                addCommand("function $namespaceID")
            }
            else -> TODO()
        }
        //static关键字，将值传回
        staticArgRef(args)
        //销毁指针，释放堆内存
        for (p in field.allVars){
            if (p is ClassPointer){
                p.dispose()
            }
        }
        //调用完毕，将子函数的栈销毁
        addCommand("data remove storage mcfpp:system " + Project.defaultNamespace + ".stack_frame[0]")
        //取出栈内的值
        fieldRestore()
    }

    /**
     * 调用这个函数。这个函数是结构体的成员方法
     *
     * @param args
     * @param struct
     */
    open fun invoke(args: ArrayList<Var>, struct: StructBase){
        TODO()
    }

    /**
     * 在创建函数栈，调用函数之前，将参数传递到函数栈中
     *
     * @param args
     */
    @InsertCommand
    open fun argPass(args: ArrayList<Var>){
        for (i in 0 until params.size) {
            when (params[i].type) {
                "int" -> {
                    val tg = args[i].cast(params[i].type) as MCInt
                    //参数传递和子函数的参数进栈
                    val p = MCInt(this,"_param_" + params[i].identifier)
                    p.assign(tg)
                    p.toDynamic()
                }
                else -> {
                    //是引用类型，不用传递
                }
            }
        }
    }

    /**
     * 在函数执行完毕，销毁函数栈之前，将函数参数中的static参数的值返回到函数调用栈中
     *
     * @param args
     */
    @InsertCommand
    open fun staticArgRef(args: ArrayList<Var>){
        var hasAddComment = false
        for (i in 0 until params.size) {
            if (params[i].isStatic) {
                if(!hasAddComment){
                    addCommand("#[Function ${this.namespaceID}] Static arguments")
                    hasAddComment = true
                }
                //如果是static参数
                if (args[i] is MCInt) {
                    when(params[i].type){
                        "int" -> {
                            //如果是int取出到记分板
                            addCommand(
                                "execute " +
                                        "store result score ${(args[i] as MCInt).name} ${(args[i] as MCInt).`object`} " +
                                        "run data get storage mcfpp:system ${Project.defaultNamespace}.stack_frame[0].${params[i].identifier} int 1 "
                            )
                        }
                        else -> {
                            //引用类型，不用还原
                        }
                    }
                }
            }
        }
    }


    /**
     * 在函数执行完毕，销毁函数栈之后，将调用栈中的变量值还原到变量中
     *
     */
    @InsertCommand
    open fun fieldRestore(){
        addCommand("#[Function ${this.namespaceID}] Take vars out of the Stack")
        Function.currFunction.field.forEachVar {v ->
            run {
                when (v.type) {
                    "int" -> {
                        val tg = v as MCInt
                        //参数传递和子函数的参数压栈
                        //如果是int取出到记分板
                        addCommand(
                            "execute store result score ${tg.name} ${tg.`object`} run "
                                    + "data get storage mcfpp:system ${Project.currNamespace}.stack_frame[0].${tg.identifier}"
                        )
                    }
                    else -> {
                        //是引用类型，不用还原
                    }
                }
            }
        }
    }

    /**
     * 判断两个函数是否相同.判据包括:命名空间ID,是否是类成员,父类和参数列表
     * @param other 要比较的对象
     * @return 若相同,则返回true
     */
    @Override
    override fun equals(other: Any?): Boolean {
        if (other is Function) {
            if (!(other.ownerType == ownerType && other.namespaceID == namespaceID && other.field === field)) {
                return false
            }
            if (other.params.size == params.size) {
                for (i in 0 until other.params.size) {
                    if (other.params[i].type != params[i].type) {
                        return false
                    }
                }
                return true
            }
        }
        return false
    }

    /**
     * 获取函数所在的类。可能不存在
     *
     * @return 返回这个函数所在的类，如果不存在则返回null
     */
    @Override
    override fun parentClass(): Class? {
        return if (ownerType == OwnerType.CLASS) {
            owner as Class
        } else null
    }

    /**
     * 获取函数所在的结构体。可能不存在
     *
     * @return 返回这个函数所在的类，如果不存在则返回null
     */
    @Override
    override fun parentStruct(): Struct? {
        return if (ownerType == OwnerType.STRUCT) {
            owner as Struct
        } else null
    }

    /**
     * 返回由函数的类（如果有），函数的标识符，函数的返回值以及函数的形参类型组成的字符串
     *
     * 类的命名空间:类名@方法名(参数)
     * @return
     */
    open fun toString(containClassName: Boolean, containNamespace: Boolean): String {
        //类名
        val clsName = if(containClassName && owner != null) owner!!.identifier else ""
        //参数
        val paramStr = StringBuilder()
        for (i in params.indices) {
            if(params[i].isStatic){
                paramStr.append("static ")
            }
            paramStr.append(params[i].type + " " + params[i].identifier)
            if (i != params.size - 1) {
                paramStr.append(",")
            }
        }
        if(containNamespace){
            return "$namespace:$clsName$identifier($paramStr)"
        }
        return "$returnType $clsName$identifier($paramStr)"
    }

    override fun hashCode(): Int {
        return namespaceID.hashCode()
    }

    companion object {
        /**
         * 一个空的函数，通常用于作为占位符
         */
        var nullFunction = Function("null")

        /**
         * 目前编译器处在的函数。允许编译器在全局获取并访问当前正在编译的函数对象。默认为全局初始化函数
         */
        var currFunction: Function = nullFunction

        /**
         * 编译器目前所处的非匿名函数
         */
        val currBaseFunction: Function
            get() {
                var ret = currFunction
                while(ret is InternalFunction){
                    ret = ret.parent[0]
                }
                return ret
            }

        fun replaceCommand(command: String, index: Int){
            replaceCommand(Command(command),index)
        }

        fun replaceCommand(command: Command, index: Int){
            if(CompileSettings.isDebug){
                //检查当前方法是否有InsertCommand注解
                val stackTrace = Thread.currentThread().stackTrace
                //调用此方法的类名
                val className = stackTrace[2].className
                //调用此方法的方法名
                val methodName = stackTrace[2].methodName
                //调用此方法的代码行数
                val lineNumber = stackTrace[2].lineNumber
                val methods: Array<Method> = java.lang.Class.forName(className).declaredMethods
                for (method in methods) {
                    if (method.name == methodName) {
                        if (!method.isAnnotationPresent(InsertCommand::class.java)) {
                            Project.warn("(JVM)Function.addCommand() was called in a method without the @InsertCommand annotation. at $className.$methodName:$lineNumber\"")
                        }
                        break
                    }
                }
            }
            if(this.equals(nullFunction)){
                Project.error("Unexpected command added to NullFunction")
                throw NullPointerException()
            }
            currFunction.commands[index] = command
        }

        fun addCommand(command: Array<Command>){
            command.forEach { addCommand(it) }
        }

        fun addCommand(command: String): Int{
            return addCommand(Command.build(command))
        }

        /**
         * 向此函数的末尾添加一条命令。
         * @param command 要添加的命令。
         */
        fun addCommand(command: Command): Int {
            if(CompileSettings.isDebug){
                //检查当前方法是否有InsertCommand注解
                val stackTrace = Thread.currentThread().stackTrace
                //调用此方法的类名
                val className = stackTrace[2].className
                //调用此方法的方法名
                val methodName = stackTrace[2].methodName
                //调用此方法的代码行数
                val lineNumber = stackTrace[2].lineNumber
                val methods: Array<Method> = java.lang.Class.forName(className).declaredMethods
                for (method in methods) {
                    if (cache.contains(method.toGenericString())){
                        break
                    }
                    cache.add(method.toGenericString())
                    if (method.name == methodName) {
                        if (!method.isAnnotationPresent(InsertCommand::class.java)) {
                            Project.warn("(JVM)Function.addCommand() was called in a method without the @InsertCommand annotation. at $className.$methodName:$lineNumber\"")
                        }
                        break
                    }
                }
            }
            if(this.equals(nullFunction)){
                Project.error("Unexpected command added to NullFunction")
                throw NullPointerException()
            }
            if (!currFunction.isEnd) {
                currFunction.commands.add(command)
            }
            return currFunction.commands.size
        }

        /**
         * 向此函数的末尾添加一行注释。
         *
         * @param str
         */
        @Deprecated("Use addCommand() instead")
        fun addComment(str: String){
            if(this.equals(nullFunction)){
                Project.warn("Unexpected command added to NullFunction")
                throw NullPointerException()
            }
            if (!currFunction.isEnd) {
                currFunction.commands.add("#$str")
            }
        }

        enum class OwnerType{
            BASIC, CLASS, STRUCT, NONE
        }

        val cache = ArrayList<String>()
    }
}
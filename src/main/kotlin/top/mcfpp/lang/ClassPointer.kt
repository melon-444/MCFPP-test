package top.mcfpp.lang

import top.mcfpp.Project
import top.mcfpp.annotations.InsertCommand
import top.mcfpp.command.Command
import top.mcfpp.command.Commands
import top.mcfpp.exception.VariableConverseException
import top.mcfpp.lib.Function
import top.mcfpp.lib.*

/**
 * 一个类的指针。类的地址储存在记分板中，因此一个类的指针实际上包含了两个信息，一个是指针代表的是[哪一个类][clsType]，一个是指针指向的这个类的对象
 *[在堆中的地址][address]，即记分板的值是多少。
 *
 * 指针继承于类[Var]，然而它的[name]并没有额外的用处，因为我们并不需要关注这个指针处于哪一个类或者哪一个函数中。起到标识符作用的更多是[identifier]。
 * 事实上，指针的[name]和[identifier]拥有相同的值。
 *
 * 创建一个类的对象的时候，在分配完毕类的地址之后，将会立刻创建一个初始指针，这个指针指向了刚刚创建的对象的地址，在记分板上的名字是一个随机的uuid。
 * 而后进行的引用操作无非是把这个初始指针的记分板值赋给其他的指针。
 *
 * @see parentClass 类的核心实现
 * @see ClassObject 类的实例。指针的目标
 * @see ClassType 表示类的类型，同时也是类的静态成员的指针
 */
class ClassPointer : ClassBase {
    /**
     * 指针对应的类的类型
     */
    override var clsType: Class

    /**
     * 指针对应的类的标识符
     */
    override var type: String

    /**
     * 指针指向的类的实例
     */
    var obj: ClassObject? = null

    override val tag: String
        /**
         * 获取这个类的实例的指针实体在mcfunction中拥有的tag
         * @return 返回它的tag
         */
        get() = clsType.namespace + "_class_" + clsType.identifier + "_pointer"

    /**
     * 创建一个指针
     * @param type 指针的类型
     * @param identifier 标识符
     */
    constructor(type: Class, identifier: String) {
        clsType = type
        this.type = clsType.identifier
        this.identifier = identifier
    }

    /**
     * 复制一个指针
     * @param classPointer 被复制的指针
     */
    constructor(classPointer: ClassPointer) : super(classPointer) {
        clsType = classPointer.clsType
        type = classPointer.type
        obj = classPointer.obj
    }

    /**
     * 将b中的值赋值给此变量。一个指针只能指向它的类型的类或者其子类的实例。
     * @param b 变量的对象
     * @throws VariableConverseException 如果隐式转换失败
     */
    @Override
    @InsertCommand
    @Throws(VariableConverseException::class)
    override fun assign(b: Var?) {
        hasAssigned = true
        //TODO 不支持指针作为类成员的时候
        when (b) {
            is ClassObject -> {
                if (!b.clsType.canCastTo(clsType)) {
                    throw VariableConverseException()
                }
                if (obj != null) {
                    //原实体中的实例减少一个指针
                    val c = Commands.selectRun(this)
                    c.last().build(Commands.sbPlayerRemove(MCInt("@s").setObj(SbObject.MCFPP_POINTER_COUNTER) as MCInt, 1))
                    Function.addCommand(c)
                }
                obj = b
                //地址储存
                Function.addCommand(Command.build(
                    "data modify storage mcfpp:system ${Project.currNamespace}.stack_frame[${stackIndex}].${identifier} " +
                            "set from storage mcfpp:temp INIT"))
                //实例中的指针列表
                val c = Commands.selectRun(this)
                Function.addCommand(c.last().build(Commands.sbPlayerAdd(MCInt("@s").setObj(SbObject.MCFPP_POINTER_COUNTER) as MCInt, 1)))
            }

            is ClassPointer -> {
                if (!b.clsType.canCastTo(clsType)) {
                    throw VariableConverseException()
                }
                if (obj != null) {
                    //原实体中的实例减少一个指针
                    val c = Commands.selectRun(this)
                    c.last().build(Commands.sbPlayerRemove(MCInt("@s").setObj(SbObject.MCFPP_POINTER_COUNTER) as MCInt, 1))
                    Function.addCommand(c)
                }
                obj = b.obj
                //地址储存
                Function.addCommand(Command.build(
                    "data modify storage mcfpp:system ${Project.currNamespace}.stack_frame[${stackIndex}].${identifier} " +
                            "set from storage mcfpp:system ${Project.currNamespace}.stack_frame[${b.stackIndex}].${b.identifier}"))
                //实例中的指针列表
                val c = Commands.selectRun(this)
                Function.addCommand(c.last().build(Commands.sbPlayerAdd(MCInt("@s").setObj(SbObject.MCFPP_POINTER_COUNTER) as MCInt, 1)))
            }
            else -> {
                throw VariableConverseException()
            }
        }
    }

    /**
     * 销毁一个指针
     *
     */
    @InsertCommand
    fun dispose(){
        if (obj != null) {
            //原实体中的实例减少一个指针
            val c = Commands.selectRun(this)
            c.last().build(Commands.sbPlayerRemove(MCInt("@s").setObj(SbObject.MCFPP_POINTER_COUNTER) as MCInt, 1))
            Function.addCommand(c)
        }
    }

    @Override
    override fun cast(type: String): Var? {
        TODO()
    }

    @Override
    override fun clone(): ClassPointer {
        return ClassPointer(this)
    }

    /**
     * 获取这个指针指向的对象中的一个成员字段。
     *
     * @param key 字段的标识符
     * @param accessModifier 访问者的访问权限
     * @return 第一个值是对象中获取到的字段，若不存在此字段则为null；第二个值是是否有足够的访问权限访问此字段。如果第一个值是null，那么第二个值总是为true
     */
    @Override
    override fun getMemberVar(key: String, accessModifier: Member.AccessModifier): Pair<Var?, Boolean> {
        val member = clsType.getVar(key)?.clone(this)
        return if(member == null){
            Pair(null, true)
        }else{
            Pair(member, accessModifier >= member.accessModifier)
        }
    }

    /**
     * 获取这个指针指向的对象中的一个成员方法。
     *
     * @param key 方法的标识符
     * @param params 方法的参数
     * @param accessModifier 访问者的访问权限
     * @return 第一个值是对象中获取到的方法，若不存在此方法则为null；第二个值是是否有足够的访问权限访问此方法。如果第一个值是null，那么第二个值总是为true
     */
    @Override
    override fun getMemberFunction(key: String, params: List<String>, accessModifier: Member.AccessModifier): Pair<Function?, Boolean> {
        //获取函数
        val member = clsType.field.getFunction(key, params)
        return if(member == null){
            Pair(null, true)
        }else{
            Pair(member, accessModifier >= member.accessModifier)
        }
    }

    /**
     * 获取一个临时的变量，一般用于四则计算的时候。
     *
     * @return 一个此变量生成的临时变量
     */
    @Override
    override fun getTempVar(): Var {
        return this
    }

    override fun storeToStack() {
        TODO("Not yet implemented")
    }

    override fun getFromStack() {
        TODO("Not yet implemented")
    }

    override fun toDynamic() {
        TODO("Not yet implemented")
    }

    companion object{
        const val tempItemEntityUUID = "810d6071-f121-4972-80d6-60cc19b40cf8"
        const val tempItemEntityUUIDNBT = "[I;-2129829775,-249476750,-2133434164,431230200]"
    }
}
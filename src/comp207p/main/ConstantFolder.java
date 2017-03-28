package comp207p.main;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;

import org.apache.bcel.classfile.*;
import org.apache.bcel.generic.*;
import org.apache.bcel.util.InstructionFinder;


public class ConstantFolder
{
	ClassParser parser = null;
	ClassGen gen = null;

	JavaClass original = null;
	JavaClass optimized = null;

	// Constants for arithmetic operations
	// Note: negation and modulo not supported.
	public static final String OP_ADD = "add";
	public static final String OP_SUB = "sub";
	public static final String OP_MUL = "mul";
	public static final String OP_DIV = "div";

	public ConstantFolder(String classFilePath)
	{
		try{
			this.parser = new ClassParser(classFilePath);
			this.original = this.parser.parse();
			this.gen = new ClassGen(this.original);
		} catch(IOException e){
			e.printStackTrace();
		}
	}
	
	public void optimize()
	{
		ClassGen cgen = new ClassGen(original);
		ConstantPoolGen cpgen = cgen.getConstantPool();

		// Implement your optimization here
		System.out.println("Starting optimisation on class " + cgen.getClassName());

		// Get the methods in the class.
		Method[] methods = cgen.getMethods();
		for (Method m : methods) {
			// Loop through each method, optimizing each.
			System.out.println("* Optimizing method " + m.getName() + "...");
			optimizeMethod(cgen, cpgen, m);
		}
        
		this.optimized = cgen.getJavaClass();
	}


	private void optimizeMethod(ClassGen cgen, ConstantPoolGen cpgen, Method m) {
		doSimpleFolding(cgen, cpgen, m);
		doConstantVariableFolding(cgen, cpgen, m);
		doDynamicVariableFolding(cgen, cpgen, m);
	}

	private void doSimpleFolding(ClassGen cgen, ConstantPoolGen cpgen, Method m) {
		System.out.println("* * Optimization 01: Simple Folding --------------");
		boolean optimizationPerformed = false;

		// Instantiate a MethodGen from the existing method.
		MethodGen methodGen = new MethodGen(m, cgen.getClassName(), cpgen);

		InstructionList il = methodGen.getInstructionList();
		InstructionFinder f = new InstructionFinder(il);
		// Note: This pattern does not handle increment or negation operations. (Should we?)
		String pattern = "LDC LDC ArithmeticInstruction";

		// Info: InstructionHandle is a wrapper for actual Instructions

		for (Iterator it = f.search(pattern); it.hasNext(); /* empty increment */) {
			InstructionHandle[] match = (InstructionHandle[])it.next();

			System.out.println("Instruction len: " + match.length);
			for (InstructionHandle ih : match) {
				System.out.println("Instruction: " + ih.getInstruction().getClass().getSimpleName());
			}

			CPInstruction 		  leftOperand, rightOperand;
			ArithmeticInstruction operator;

			// match[0] expected to be LDC, as specified in the pattern.
			leftOperand  = (CPInstruction) match[0].getInstruction();

			// match[1] expected to be LDC, as specified in the pattern.
			rightOperand = (CPInstruction) match[1].getInstruction();

			// match[2] expected to be ArithmeticInstruction, as specified in the pattern.
			operator = (ArithmeticInstruction) match[2].getInstruction();

			// Assert that we have the right types.
			assert (leftOperand != null && rightOperand != null && operator != null) : "Operands or operator of unexpected type!";

			// Assert that both operands have natural values (i.e. numbers).
			assert cpgen.getConstant(leftOperand.getIndex())  instanceof ConstantObject;
			assert cpgen.getConstant(rightOperand.getIndex()) instanceof ConstantObject;

			// Grab the constants from the constant pool.
			ConstantObject leftConst  = (ConstantObject) cpgen.getConstant(leftOperand.getIndex());
			ConstantObject rightConst = (ConstantObject) cpgen.getConstant(rightOperand.getIndex());

			Number leftNum, rightNum;

			leftNum  = (Number) leftConst.getConstantValue(cpgen.getConstantPool());
			rightNum = (Number) rightConst.getConstantValue(cpgen.getConstantPool());

			// Fold the constant by type.
			Type   operatorType = operator.getType(cpgen);
			String operationStr = operator.getName().substring(1);	// 'add', 'mul', 'sub', 'div'

			System.out.println("leftNum: " + leftNum + " rightNum: " + rightNum + " type: " + operatorType + " operation: " + operationStr);

			Number foldedValue = doArithmeticOperation(leftNum, rightNum, operatorType, operationStr);

			if (foldedValue != null) {
				System.out.println("Folded value: " + foldedValue + " type: " + foldedValue.getClass().getName());

				// The index of the new value
				int cpIndex = -1;

				// Add result to constant pool.
				if (operatorType == Type.INT) {
					cpIndex = cpgen.addInteger(foldedValue.intValue());
				} else if (operatorType == Type.LONG) {
					cpIndex = cpgen.addLong(foldedValue.longValue());
				} else if (operatorType == Type.FLOAT) {
					cpIndex = cpgen.addFloat(foldedValue.floatValue());
				} else if (operatorType == Type.DOUBLE) {
					cpIndex = cpgen.addDouble(foldedValue.doubleValue());
				}

				System.out.format("New constant pool entry with index %d and value %d.\n", cpIndex, foldedValue);

				if (cpIndex > -1) {
					// Insert new LDC instruction to load from our new constant pool entry.
					il.insert(match[0], new LDC(cpIndex));
					try {
						// Delete old instructions (LDC, LDC, OP)
						il.delete(match[0], match[2]);
					} catch (TargetLostException e) {
						e.printStackTrace();
					}

					optimizationPerformed = true;
					System.out.println("Optimization performed.");
				}

			} else {
				System.out.format("WARNING: Folding fallthrough. Unsupported type %s - no optimization performed.\n", operatorType);
			}
		}

		if (optimizationPerformed) {
			// setPositions(true) checks whether jump handles
			// are all within the current method
			il.setPositions(true);

			// Recompute max stack/locals.
			methodGen.setMaxStack();
			methodGen.setMaxLocals();

			// Generate the new method.
			Method newMethod = methodGen.getMethod();

			// Replace the method in the original class.
			cgen.replaceMethod(m, newMethod);

		} else {
			System.out.println("* * No optimization required.");
		}

		// Dispose so that instruction handles can be reused. (Just good practice.)
		il.dispose();
	}

	private void doConstantVariableFolding(ClassGen cgen, ConstantPoolGen cpgen, Method m) {

	}

	private void doDynamicVariableFolding(ClassGen cgen, ConstantPoolGen cpgen, Method m) {

	}

	// ===========================
	// ======== UTILITIES ========
	// ===========================
	private Number doArithmeticOperation(Number lhs, Number rhs, Type operatorType, String operationStr) {
		Number result = null;
		switch (operationStr) {
			case OP_ADD:
				if (operatorType == Type.INT) {
					result = lhs.intValue() + rhs.intValue();
				} else if (operatorType == Type.LONG) {
					result = lhs.longValue() + rhs.longValue();
				} else if (operatorType == Type.FLOAT) {
					result = lhs.floatValue() + rhs.floatValue();
				} else if (operatorType == Type.DOUBLE) {
					result = lhs.doubleValue() + rhs.doubleValue();
				}
				break;
			case OP_SUB:
				if (operatorType == Type.INT) {
					result = lhs.intValue() - rhs.intValue();
				} else if (operatorType == Type.LONG) {
					result = lhs.longValue() - rhs.longValue();
				} else if (operatorType == Type.FLOAT) {
					result = lhs.floatValue() - rhs.floatValue();
				} else if (operatorType == Type.DOUBLE) {
					result = lhs.doubleValue() - rhs.doubleValue();
				}
				break;
			case OP_MUL:
				if (operatorType == Type.INT) {
					result = lhs.intValue() * rhs.intValue();
				} else if (operatorType == Type.LONG) {
					result = lhs.longValue() * rhs.longValue();
				} else if (operatorType == Type.FLOAT) {
					result = lhs.floatValue() * rhs.floatValue();
				} else if (operatorType == Type.DOUBLE) {
					result = lhs.doubleValue() * rhs.doubleValue();
				}
				break;
			case OP_DIV:
				if (operatorType == Type.INT) {
					result = lhs.intValue() / rhs.intValue();
				} else if (operatorType == Type.LONG) {
					result = lhs.longValue() / rhs.longValue();
				} else if (operatorType == Type.FLOAT) {
					result = lhs.floatValue() / rhs.floatValue();
				} else if (operatorType == Type.DOUBLE) {
					result = lhs.doubleValue() / rhs.doubleValue();
				}
				break;
		}
		return result;
	}


	public void write(String optimisedFilePath)
	{
		this.optimize();

		try {
			FileOutputStream out = new FileOutputStream(new File(optimisedFilePath));
			this.optimized.dump(out);
		} catch (FileNotFoundException e) {
			// Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// Auto-generated catch block
			e.printStackTrace();
		}
	}
}
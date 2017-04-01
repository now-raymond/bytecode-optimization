package comp207p.main;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.HashMap;
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

		// Set major version to allow for a non-updated StackMapTable that BCEL cannot generate.
		cgen.setMajor(50);

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

		// Instantiate a MethodGen from the existing method.
		MethodGen methodGen = new MethodGen(m, cgen.getClassName(), cpgen);

		InstructionList il = methodGen.getInstructionList();

		boolean optimizationPerformed;
		do {
			InstructionFinder f = new InstructionFinder(il);
			// Note: This pattern does not handle increment or negation operations. (Should we?)
			String pattern = "(LDC|LDC2_W) (LDC|LDC2_W) ArithmeticInstruction";
			// TODO
			//String pattern = "(LDC|LDC2_W|ConstantPushInstruction) (LDC|LDC2_W|ConstantPushInstruction) ArithmeticInstruction";

			// Info: InstructionHandle is a wrapper for actual Instructions

			optimizationPerformed = false;
			for (Iterator it = f.search(pattern); it.hasNext(); /* empty increment */) {
				InstructionHandle[] match = (InstructionHandle[]) it.next();

				System.out.println("Instruction len: " + match.length);
				for (InstructionHandle ih : match) {
					System.out.println("Instruction: " + ih.getInstruction().getClass().getSimpleName());
				}

				CPInstruction leftOperand, rightOperand;
				ArithmeticInstruction operator;

				// TODO
				/*if (match[0].getInstruction() instanceof ConstantPushInstruction) {

				} else {

				}

				if (match[1].getInstruction() instanceof ConstantPushInstruction) {

				} else {

				}*/

				// match[0] expected to be LDC, as specified in the pattern.
				leftOperand = (CPInstruction) match[0].getInstruction();

				// match[1] expected to be LDC, as specified in the pattern.
				rightOperand = (CPInstruction) match[1].getInstruction();

				// match[2] expected to be ArithmeticInstruction, as specified in the pattern.
				operator = (ArithmeticInstruction) match[2].getInstruction();

				// Assert that we have the right types.
				assert (leftOperand != null && rightOperand != null && operator != null) : "Operands or operator of unexpected type!";

				// Assert that both operands have natural values (i.e. numbers).
				assert cpgen.getConstant(leftOperand.getIndex()) instanceof ConstantObject;
				assert cpgen.getConstant(rightOperand.getIndex()) instanceof ConstantObject;

				// Grab the constants from the constant pool.
				ConstantObject leftConst = (ConstantObject) cpgen.getConstant(leftOperand.getIndex());
				ConstantObject rightConst = (ConstantObject) cpgen.getConstant(rightOperand.getIndex());

				Number leftNum, rightNum;

				leftNum = (Number) leftConst.getConstantValue(cpgen.getConstantPool());
				rightNum = (Number) rightConst.getConstantValue(cpgen.getConstantPool());

				// Fold the constant by type.
				Type operatorType = operator.getType(cpgen);
				String operationStr = operator.getName().substring(1);    // 'iadd', 'fmul', etc. -> 'add', 'mul', 'sub', 'div'

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

					System.out.println("New constant pool entry with index " + cpIndex + " and value " + foldedValue);

					if (cpIndex > -1) {
						// Insert new LDC instruction to load from our new constant pool entry.
						//il.insert(match[0], new LDC(cpIndex));

						// Use reflection to dynamically instantiate the right class.
						Constructor<?> ldcConstructor;
						CPInstruction cpInstruction = null;
						try {
							ldcConstructor = match[0].getInstruction().getClass().getConstructor(Integer.TYPE);
							cpInstruction = (CPInstruction) ldcConstructor.newInstance(cpIndex);
						} catch (Exception e) {
							e.printStackTrace();
						}
						il.insert(match[0], cpInstruction);

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
		} while (optimizationPerformed);

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

		// Dispose so that instruction handles can be reused. (Just good practice.)
		il.dispose();
	}

	private void doConstantVariableFolding(ClassGen cgen, ConstantPoolGen cpgen, Method m) {
		System.out.println("* * Optimization 02: Constant Variable Folding --------------");

		// This hashmap stores all literal values that we know about.
		HashMap<Integer,Number> literalValues = new HashMap<>();

		// This hashmap stores a list of local variables that are *constant* for this method.
		// constantVariables.get(index) is TRUE if the variable is constant.
		HashMap<Integer, Boolean> constantVariables = new HashMap<>();

		// Instantiate a MethodGen from the existing method.
		MethodGen methodGen = new MethodGen(m, cgen.getClassName(), cpgen);

		InstructionList il = methodGen.getInstructionList();
		InstructionFinder f = new InstructionFinder(il);

		// Locate constant local variables that do not change for this method.
		String pattern = "StoreInstruction";
		for (Iterator it = f.search(pattern); it.hasNext(); /* empty increment */) {
			InstructionHandle[] match = (InstructionHandle[]) it.next();

			// match[0] expected to be StoreInstruction, as specified in the pattern.
			StoreInstruction storeInstruction = (StoreInstruction) match[0].getInstruction();

			System.out.format("storeInstruction: %s index: %s\n", storeInstruction.getClass().getSimpleName(), storeInstruction.getIndex());

			// See if we've already tracked this local variable.
			if (!constantVariables.containsKey(storeInstruction.getIndex())) {
				constantVariables.put(storeInstruction.getIndex(), true);
			} else {
				// We've seen this index before.. mark it as NOT constant.
				constantVariables.put(storeInstruction.getIndex(), false);
			}
		}

		// The loop will end when there are no longer any LoadInstructions whose index exists in the literalValues hashmap.
		boolean foldedLoadInstruction;
		do {
			// Run simple folding to get as many literals as possible.
			doSimpleFolding(cgen, cpgen, m);

			// Store all literals in the hashmap.
			// e.g. LDC #2, ISTORE_1
			// e.g. SIPUSH 1234, ISTORE_2
			f = new InstructionFinder(il);
			String pattern2 = "(LDC | LDC2_W | LDC_W | ConstantPushInstruction) (DSTORE | FSTORE | ISTORE | LSTORE)";
			for (Iterator it = f.search(pattern2); it.hasNext(); /* empty increment */) {
				InstructionHandle[] match = (InstructionHandle[]) it.next();

				// match[0] expected to be PushInstruction, as specified in the pattern (it's the superclass of the specified pattern).
				PushInstruction pushInstruction = (PushInstruction) match[0].getInstruction();

				// match[1] expected to be StoreInstruction, as specified in the pattern.
				StoreInstruction storeInstruction = (StoreInstruction) match[1].getInstruction();

				// Check if this store instruction is for a constant variable.
				if (!constantVariables.get(storeInstruction.getIndex())) {
					// If the variable isn't constant, skip this iteration.
					continue;
				}

				Number literalValue = null;

				// Get the constant value pushed.
				if (pushInstruction instanceof ConstantPushInstruction) {
					literalValue = ((ConstantPushInstruction) pushInstruction).getValue();
				} else if (pushInstruction instanceof LDC) {
					// LDC must be Number since we only accept ILOAD, FLOAD, etc.
					literalValue = (Number) ((LDC) pushInstruction).getValue(cpgen);
				} else if (pushInstruction instanceof LDC2_W) {
					literalValue = ((LDC2_W) pushInstruction).getValue(cpgen);
				}

				// Assert that we've assigned a value to literalValue.
				assert literalValue != null;

				System.out.format("pushInstruction: %s storeInstruction: %s index: %d value: %f\n", pushInstruction.getClass().getSimpleName(), storeInstruction.getClass().getSimpleName(), storeInstruction.getIndex(), literalValue.doubleValue());

				// Store the literal value in the literalValues hashmap.
				literalValues.put(storeInstruction.getIndex(), literalValue);
			}

			// Look for LoadInstruction and check if the index exists in the hashmap.
			// If it does, replace the LoadInstruction with the literal value.
			foldedLoadInstruction = false;
			f = new InstructionFinder(il);
			String pattern3 = "(DLOAD | FLOAD | ILOAD | LLOAD)";
			for (Iterator it = f.search(pattern3); it.hasNext(); /* empty increment */) {
				InstructionHandle[] match = (InstructionHandle[]) it.next();

				// match[0] expected to be LoadInstruction, as specified in the pattern (it's the superclass of the specified pattern).
				LoadInstruction loadInstruction = (LoadInstruction) match[0].getInstruction();

				System.out.format("loadInstruction: %s index: %s\n", loadInstruction.getClass().getSimpleName(), loadInstruction.getIndex());

				// Check if the index exists in the hashmap.
				if (literalValues.containsKey(loadInstruction.getIndex())) {
					// Yes, it does!
					// TODO: Replace the LoadInstruction with the literal value.

					Number literalValue = literalValues.get(loadInstruction.getIndex());

					Instruction instructionAdded = null;

					if (loadInstruction.getType(cpgen) == Type.INT) {
						if (Math.abs(literalValue.intValue()) < Byte.MAX_VALUE) {
							instructionAdded = new BIPUSH(literalValue.byteValue());
						} else if (Math.abs(literalValue.intValue()) < Short.MAX_VALUE) {
							instructionAdded = new SIPUSH(literalValue.shortValue());
						} else {
							// We need to add to the constant pool.
							instructionAdded = new LDC(cpgen.addInteger(literalValue.intValue()));
						}
					} else if (loadInstruction.getType(cpgen) == Type.FLOAT) {
						// Need to add to the constant pool.
						instructionAdded = new LDC(cpgen.addFloat(literalValue.floatValue()));
					} else if (loadInstruction.getType(cpgen) == Type.DOUBLE) {
						// Need to add to the constant pool.
						instructionAdded = new LDC2_W(cpgen.addDouble(literalValue.doubleValue()));
					} else if (loadInstruction.getType(cpgen) == Type.LONG) {
						// Need to add to the constant pool.
						instructionAdded = new LDC2_W(cpgen.addLong(literalValue.longValue()));
					}

					// Assert that there's an instruction to add.
					assert instructionAdded != null;

					il.insert(match[0], instructionAdded);

					try {
						// Delete old instructions (loadInstruction)
						il.delete(match[0]);
					} catch (TargetLostException e) {
						e.printStackTrace();
					}

					foldedLoadInstruction = true;

					System.out.format("Replaced %s %d with %s %f.\n", loadInstruction.getClass().getSimpleName(), loadInstruction.getIndex(), instructionAdded.getClass().getSimpleName(), literalValue.doubleValue());
				}
			}
		} while (foldedLoadInstruction);

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

		// Dispose so that instruction handles can be reused. (Just good practice.)
		il.dispose();
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
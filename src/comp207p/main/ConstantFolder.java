package comp207p.main;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
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
		// 1. Instantiate a MethodGen from the existing method.
		MethodGen methodGen = new MethodGen(m, cgen.getClassName(), cpgen);
		InstructionList il = methodGen.getInstructionList();

		// 2. Perform optimizations.
		doSimpleFolding(cgen, cpgen, il);
		doConstantVariableFolding(cgen, cpgen, il, null, null);
		doDynamicVariableFolding(cgen, cpgen, il);

		// 3. Replace method.
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

	private void doSimpleFolding(ClassGen cgen, ConstantPoolGen cpgen, InstructionList il) {
		System.out.println("* * Optimization 01: Simple Folding --------------");

		boolean optimizationPerformed;
		do {
			InstructionFinder f = new InstructionFinder(il);
			// ConstantPushInstruction: BIPUSH, SIPUSH, ICONST, etc.
			// ConversionInstruction: I2D, D2F, etc.
			String pattern = "(LDC|LDC2_W|ConstantPushInstruction) (LDC|LDC2_W|ConstantPushInstruction) ConversionInstruction? ArithmeticInstruction";

			// Info: InstructionHandle is a wrapper for actual Instructions

			optimizationPerformed = false;
			for (Iterator it = f.search(pattern); it.hasNext(); /* empty increment */) {
				InstructionHandle[] match = (InstructionHandle[]) it.next();

				System.out.println("Instruction len: " + match.length);
				for (InstructionHandle ih : match) {
					System.out.println("Instruction: " + ih.getInstruction().getClass().getSimpleName());
				}

				Number leftNum = null;
				Number rightNum = null;
				ArithmeticInstruction operator;
				ConversionInstruction conversionInstruction = null;	// May be null

				// Check type of left operand.
				if (match[0].getInstruction() instanceof ConstantPushInstruction) {
					leftNum = ((ConstantPushInstruction) match[0].getInstruction()).getValue();
				} else if (match[0].getInstruction() instanceof LDC) {
					leftNum = (Number) ((LDC) match[0].getInstruction()).getValue(cpgen);
				} else if (match[0].getInstruction() instanceof LDC2_W) {
					leftNum = (Number) ((LDC2_W) match[0].getInstruction()).getValue(cpgen);
				}

				// Check type of right operand.
				if (match[1].getInstruction() instanceof ConstantPushInstruction) {
					rightNum = ((ConstantPushInstruction) match[1].getInstruction()).getValue();
				} else if (match[1].getInstruction() instanceof LDC) {
					rightNum = (Number) ((LDC) match[1].getInstruction()).getValue(cpgen);
				} else if (match[1].getInstruction() instanceof LDC2_W) {
					rightNum = (Number) ((LDC2_W) match[1].getInstruction()).getValue(cpgen);
				}

				// Check if optional ConversionInstruction is present.
				if (match[2].getInstruction() instanceof ConversionInstruction) {
					conversionInstruction = (ConversionInstruction) match[2].getInstruction();

					// match[3] expected to be ArithmeticInstruction, as specified in the pattern.
					operator = (ArithmeticInstruction) match[3].getInstruction();
				} else {
					// Optional ConversionInstruction not present.
					// match[2] expected to be ArithmeticInstruction, as specified in the pattern.
					operator = (ArithmeticInstruction) match[2].getInstruction();
				}

				// Assert that we have the right types.
				if (leftNum == null || rightNum == null || operator == null) {
					System.err.println("FATAL: Operands or operator of unexpected type!");
				};

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
					} else if (operatorType == Type.FLOAT) {
						cpIndex = cpgen.addFloat(foldedValue.floatValue());
					} else if (operatorType == Type.LONG) {
						cpIndex = cpgen.addLong(foldedValue.longValue());
					} else if (operatorType == Type.DOUBLE) {
						cpIndex = cpgen.addDouble(foldedValue.doubleValue());
					}

					System.out.println("New constant pool entry with index " + cpIndex + " and value " + foldedValue);

					if (cpIndex > -1) {
						// Insert new LDC instruction to load from our new constant pool entry.

						InstructionHandle instructionAddedHandle = null;
						if (operatorType == Type.INT || operatorType == Type.FLOAT) {
							instructionAddedHandle = il.insert(match[0], new LDC(cpIndex));
						} else if (operatorType == Type.LONG || operatorType == Type.DOUBLE) {
							instructionAddedHandle = il.insert(match[0], new LDC2_W(cpIndex));
						}

						// Use reflection to dynamically instantiate the right class.
						/*Constructor<?> ldcConstructor;
						CPInstruction cpInstruction = null;
						try {
							ldcConstructor = match[0].getInstruction().getClass().getConstructor(Integer.TYPE);
							cpInstruction = (CPInstruction) ldcConstructor.newInstance(cpIndex);
						} catch (Exception e) {
							e.printStackTrace();
						}
						il.insert(match[0], cpInstruction);*/

						try {
							// Delete old instructions (LDC, LDC, OP)
							il.delete(match[0], match[2]);

							// If optional ConversionInstruction was present, delete one more.
							if (conversionInstruction != null) {
								il.delete(match[3]);
							}
						} catch (TargetLostException e) {
							for (InstructionHandle target : e.getTargets()) {
								for (InstructionTargeter targeter : target.getTargeters()) {
									if (instructionAddedHandle != null) {
										targeter.updateTarget(target, instructionAddedHandle);
									} else {
										System.err.println("Failed to fix targets to this instruction");
										e.printStackTrace();
									}
								}
							}
							//e.printStackTrace();
						}

						optimizationPerformed = true;
						System.out.println("Optimization performed.");
					}

				} else {
					System.out.format("WARNING: Folding fallthrough. Unsupported type %s - no optimization performed.\n", operatorType);
				}
			}
		} while (optimizationPerformed);
	}

	/**
	 * Performs constant variable folding optimization.
	 * Pass NULL to startHandle and endHandle to have them automatically generated from the instruction list.
	 * If specified, search starts at startHandle (inclusive), and ends at endHandle (inclusive).
     */
	private void doConstantVariableFolding(ClassGen cgen, ConstantPoolGen cpgen, InstructionList il, InstructionHandle startHandle, InstructionHandle endHandle) {
		System.out.println("* * Optimization 02: Constant Variable Folding --------------");

		// Fill defaults
		if (startHandle == null) {
			startHandle = il.getStart();
		}
		if (endHandle == null) {
			endHandle = il.getEnd();
		}

		// This hashmap stores all literal values that we know about.
		HashMap<Integer,Number> literalValues = new HashMap<>();

		// This hashmap stores a list of local variables that are *constant* for this method.
		// constantVariables.get(index) is TRUE if the variable is constant.
		HashMap<Integer, Boolean> constantVariables = new HashMap<>();

		// Locate constant local variables that do not change for this method.
		InstructionFinder f = new InstructionFinder(il);
		String pattern = "StoreInstruction | IINC";
		for (Iterator it = f.search(pattern, startHandle); it.hasNext(); /* empty increment */) {
			InstructionHandle[] match = (InstructionHandle[]) it.next();

			if (match[0].getPosition() > endHandle.getPosition()) {
				continue;
			}

			int localVariableIndex = -1;

			// match[0] expected to be StoreInstruction or IINC, as specified in the pattern.
			if (match[0].getInstruction() instanceof StoreInstruction) {
				localVariableIndex = ((StoreInstruction) match[0].getInstruction()).getIndex();
			} else if (match[0].getInstruction() instanceof IINC) {
				localVariableIndex = ((IINC) match[0].getInstruction()).getIndex();
			}

			// Assert we've assigned a value to localVariableIndex.
			if (localVariableIndex == -1) {
				System.err.println("FATAL: doConstantVariableFolding: localVariableIndex not assigned.");
			}

			System.out.format("storeInstruction: %s index: %s\n", match[0].getInstruction().getClass().getSimpleName(), localVariableIndex);

			// See if we've already tracked this local variable.
			if (!constantVariables.containsKey(localVariableIndex)) {
				constantVariables.put(localVariableIndex, true);
			} else {
				// We've seen this index before.. mark it as NOT constant.
				constantVariables.put(localVariableIndex, false);
			}
		}

		// The loop will end when there are no longer any LoadInstructions whose index exists in the literalValues hashmap.
		boolean foldedLoadInstruction;
		do {
			// Run simple folding to get as many literals as possible.
			doSimpleFolding(cgen, cpgen, il);

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
				if (!constantVariables.containsKey(storeInstruction.getIndex()) || !constantVariables.get(storeInstruction.getIndex())) {
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
				if (literalValue == null) {
					System.err.format("FATAL: Could not obtain literal value for unknown type %s.\n", pushInstruction.getClass().getSimpleName());
				}

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
					// Replace the LoadInstruction with the literal value.

					Number literalValue = literalValues.get(loadInstruction.getIndex());

					Instruction instructionAdded = null;

					if (loadInstruction.getType(cpgen) == Type.INT) {
						if (false && Math.abs(literalValue.intValue()) < Byte.MAX_VALUE) {
							instructionAdded = new BIPUSH(literalValue.byteValue());
						} else if (false && Math.abs(literalValue.intValue()) < Short.MAX_VALUE) {
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

					InstructionHandle instructionAddedHandle = il.insert(match[0], instructionAdded);

					try {
						// Delete old instructions (loadInstruction)
						il.delete(match[0]);
					} catch (TargetLostException e) {
						for (InstructionHandle target : e.getTargets()) {
							for (InstructionTargeter targeter : target.getTargeters()) {
								targeter.updateTarget(target, instructionAddedHandle);
							}
						}
						//e.printStackTrace();
					}

					foldedLoadInstruction = true;

					System.out.format("Replaced %s %d with %s %f.\n", loadInstruction.getClass().getSimpleName(), loadInstruction.getIndex(), instructionAdded.getClass().getSimpleName(), literalValue.doubleValue());
				}
			}
		} while (foldedLoadInstruction);

//		// setPositions(true) checks whether jump handles
//		// are all within the current method
//		il.setPositions(true);
//
//		// Recompute max stack/locals.
//		methodGen.setMaxStack();
//		methodGen.setMaxLocals();
//
//		// Generate the new method.
//		Method newMethod = methodGen.getMethod();
//
//		// Replace the method in the original class.
//		cgen.replaceMethod(m, newMethod);
//
//		// Dispose so that instruction handles can be reused. (Just good practice.)
//		il.dispose();
	}

	private void doDynamicVariableFolding(ClassGen cgen, ConstantPoolGen cpgen, InstructionList il) {
		System.out.println("* * Optimization 03: Dynamic Variable Folding --------------");

		// Instantiate a MethodGen from the existing method.
		InstructionFinder f = new InstructionFinder(il);

		String pattern = "((StoreInstruction) (Instruction)* (StoreInstruction))";


		int maxLocalVariableIndex = getHighestLocalVariableIndex(il);
		// System.out.println("Highest local variable index is " + maxLocalVariableIndex);

		// Work on optimizing variable in order
		for (Iterator it = f.search(pattern); it.hasNext(); /* empty increment */) {
			InstructionHandle[] match = (InstructionHandle[]) it.next();
			// Regions between two similar istores
			for(int i = 0; i < match.length-1; i++){
				Instruction startInstruction = match[i].getInstruction();
				for (int j = i+1; j < match.length; j++){
					Instruction endInstruction = match[j].getInstruction();
					if((startInstruction instanceof StoreInstruction) && (endInstruction instanceof StoreInstruction)){
						if(startInstruction.equals(endInstruction)){
							System.out.println("Found matching ISTORES!");
							il.setPositions(true);
							System.out.println("Intermediet Instructions before folding " + il);
							System.out.println("I IS :" + i);
							System.out.println("J IS :" + j);
							System.out.println("I HANDLE IS :" + match[i]);
							System.out.println("J HANDLE IS :" + match[j]);
							doConstantVariableFolding(cgen, cpgen, il, match[i+1], match[j]);
							il.setPositions(true);
							System.out.println("Intermediet Instructions after folding " + il);
						}
					}
				}
			}
		}
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

	public int getHighestLocalVariableIndex(InstructionList il){
		InstructionFinder f = new InstructionFinder(il);
		String pattern = "(Instruction)*";
		int highestCount  = 0;
		for (Iterator it = f.search(pattern); it.hasNext(); /* empty increment */) {
			InstructionHandle[] match = (InstructionHandle[]) it.next();
			for(int i = 0; i < match.length; i++){
				if(match[i].getInstruction() instanceof StoreInstruction){
					if(((StoreInstruction) match[i].getInstruction()).getIndex() > highestCount){
						highestCount = ((StoreInstruction) match[i].getInstruction()).getIndex();
					}
				}
			}
		}
		return highestCount;
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

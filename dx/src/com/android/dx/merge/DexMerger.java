/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dx.merge;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.android.dx.dex.SizeOf;
import com.android.dx.dex.TableOfContents;
import com.android.dx.dex.TableOfContents.Section;
import com.android.dx.io.Annotation;
import com.android.dx.io.ClassData;
import com.android.dx.io.ClassDef;
import com.android.dx.io.Code;
import com.android.dx.io.DexBuffer;
import com.android.dx.io.DexHasher;
import com.android.dx.io.EncodedValue;
import com.android.dx.io.FieldId;
import com.android.dx.io.MethodId;
import com.android.dx.io.ProtoId;
import com.android.dx.util.DexException;

/**
 * Combine two dex files into one.
 */
public final class DexMerger {
    private final DexBuffer dexA;
    private final DexBuffer dexB;
    private final CollisionPolicy collisionPolicy;
    private final WriterSizes writerSizes;

    private final DexBuffer dexOut = new DexBuffer();

    private final DexBuffer.Section headerOut;

    /** All IDs and definitions sections */
    private final DexBuffer.Section idsDefsOut;

    private final DexBuffer.Section mapListOut;

    private final DexBuffer.Section typeListOut;

    private final DexBuffer.Section classDataOut;

    private final DexBuffer.Section codeOut;

    private final DexBuffer.Section stringDataOut;

    private final DexBuffer.Section debugInfoOut;

    private final DexBuffer.Section encodedArrayOut;

    /** annotations directory on a type */
    private final DexBuffer.Section annotationsDirectoryOut;

    /** sets of annotations on a member, parameter or type */
    private final DexBuffer.Section annotationSetOut;

    /** parameter lists */
    private final DexBuffer.Section annotationSetRefListOut;

    /** individual annotations, each containing zero or more fields */
    private final DexBuffer.Section annotationOut;

    private final TableOfContents contentsOut;

    private final IndexMap aIndexMap;
    private final IndexMap bIndexMap;
    private final InstructionTransformer aInstructionTransformer;
    private final InstructionTransformer bInstructionTransformer;

    /** minimum number of wasted bytes before it's worthwhile to compact the result */
    private int compactWasteThreshold = 1024 * 1024; // 1MiB

    public DexMerger(DexBuffer dexA, DexBuffer dexB, CollisionPolicy collisionPolicy)
            throws IOException {
        this(dexA, dexB, collisionPolicy, new WriterSizes(dexA, dexB));
    }

    private DexMerger(DexBuffer dexA, DexBuffer dexB, CollisionPolicy collisionPolicy,
            WriterSizes writerSizes) throws IOException {
        this.dexA = dexA;
        this.dexB = dexB;
        this.collisionPolicy = collisionPolicy;
        this.writerSizes = writerSizes;

        TableOfContents aContents = dexA.getTableOfContents();
        TableOfContents bContents = dexB.getTableOfContents();
        aIndexMap = new IndexMap(dexOut, aContents);
        bIndexMap = new IndexMap(dexOut, bContents);
        aInstructionTransformer = new InstructionTransformer(aIndexMap);
        bInstructionTransformer = new InstructionTransformer(bIndexMap);

        headerOut = dexOut.appendSection(writerSizes.header, "header");
        idsDefsOut = dexOut.appendSection(writerSizes.idsDefs, "ids defs");

        contentsOut = dexOut.getTableOfContents();
        contentsOut.dataOff = dexOut.getLength();

        contentsOut.mapList.off = dexOut.getLength();
        contentsOut.mapList.size = 1;
        mapListOut = dexOut.appendSection(writerSizes.mapList, "map list");

        contentsOut.typeLists.off = dexOut.getLength();
        typeListOut = dexOut.appendSection(writerSizes.typeList, "type list");

        contentsOut.annotationSetRefLists.off = dexOut.getLength();
        annotationSetRefListOut = dexOut.appendSection(
                writerSizes.annotationsSetRefList, "annotation set ref list");

        contentsOut.annotationSets.off = dexOut.getLength();
        annotationSetOut = dexOut.appendSection(writerSizes.annotationsSet, "annotation sets");

        contentsOut.classDatas.off = dexOut.getLength();
        classDataOut = dexOut.appendSection(writerSizes.classData, "class data");

        contentsOut.codes.off = dexOut.getLength();
        codeOut = dexOut.appendSection(writerSizes.code, "code");

        contentsOut.stringDatas.off = dexOut.getLength();
        stringDataOut = dexOut.appendSection(writerSizes.stringData, "string data");

        contentsOut.debugInfos.off = dexOut.getLength();
        debugInfoOut = dexOut.appendSection(writerSizes.debugInfo, "debug info");

        contentsOut.annotations.off = dexOut.getLength();
        annotationOut = dexOut.appendSection(writerSizes.annotation, "annotation");

        contentsOut.encodedArrays.off = dexOut.getLength();
        encodedArrayOut = dexOut.appendSection(writerSizes.encodedArray, "encoded array");

        contentsOut.annotationsDirectories.off = dexOut.getLength();
        annotationsDirectoryOut = dexOut.appendSection(
                writerSizes.annotationsDirectory, "annotations directory");

        dexOut.noMoreSections();
        contentsOut.dataSize = dexOut.getLength() - contentsOut.dataOff;
    }

    public void setCompactWasteThreshold(int compactWasteThreshold) {
        this.compactWasteThreshold = compactWasteThreshold;
    }

    private DexBuffer mergeDexBuffers() throws IOException {
        mergeStringIds();
        mergeTypeIds();
        mergeTypeLists();
        mergeProtoIds();
        mergeFieldIds();
        mergeMethodIds();
        mergeAnnotations();
        mergeAnnotationSets();
        mergeAnnotationSetRefs();
        mergeAnnotationDirectory();
        mergeStaticValue();
        mergeClassDefs();

        // write the header
        contentsOut.header.off = 0;
        contentsOut.header.size = 1;
        contentsOut.fileSize = dexOut.getLength();
        contentsOut.computeSizesFromOffsets();
        contentsOut.writeHeader(headerOut);
        contentsOut.writeMap(mapListOut);

        // generate and write the hashes
        new DexHasher().writeHashes(dexOut);

        return dexOut;
    }

    public DexBuffer merge() throws IOException {
        long start = System.nanoTime();
        DexBuffer result = mergeDexBuffers();

        /*
         * We use pessimistic sizes when merging dex files. If those sizes
         * result in too many bytes wasted, compact the result. To compact,
         * simply merge the result with itself.
         */
        WriterSizes compactedSizes = new WriterSizes(this);
        int wastedByteCount = writerSizes.size() - compactedSizes.size();
//        if (wastedByteCount >  + compactWasteThreshold) {
            DexMerger compacter = new DexMerger(
                    dexOut, new DexBuffer(), CollisionPolicy.FAIL, compactedSizes);
            result = compacter.mergeDexBuffers();
            System.out.printf("Result compacted from %.1fKiB to %.1fKiB to save %.1fKiB%n",
                    dexOut.getLength() / 1024f,
                    result.getLength() / 1024f,
                    wastedByteCount / 1024f);
//        }

        long elapsed = System.nanoTime() - start;
        System.out.printf("Merged dex A (%d defs/%.1fKiB) with dex B "
                + "(%d defs/%.1fKiB). Result is %d defs/%.1fKiB. Took %.1fs%n",
                dexA.getTableOfContents().classDefs.size,
                dexA.getLength() / 1024f,
                dexB.getTableOfContents().classDefs.size,
                dexB.getLength() / 1024f,
                result.getTableOfContents().classDefs.size,
                result.getLength() / 1024f,
                elapsed / 1000000000f);

        return result;
    }

    /**
     * Reads an IDs section of two dex files and writes an IDs section of a
     * merged dex file. Populates maps from old to new indices in the process.
     */
    abstract class IdMerger<T extends Comparable<T>> {
        private final DexBuffer.Section out;

        protected IdMerger(DexBuffer.Section out) {
            this.out = out;
        }

        /**
         * Merges already-sorted sections, reading only two values into memory
         * at a time.
         */
        public final void mergeSorted() {
            TableOfContents.Section aSection = getSection(dexA.getTableOfContents());
            TableOfContents.Section bSection = getSection(dexB.getTableOfContents());
            getSection(contentsOut).off = out.getPosition();

            DexBuffer.Section inA = aSection.exists() ? dexA.open(aSection.off) : null;
            DexBuffer.Section inB = bSection.exists() ? dexB.open(bSection.off) : null;
            int aOffset = -1;
            int bOffset = -1;
            int aIndex = 0;
            int bIndex = 0;
            int outCount = 0;
            T a = null;
            T b = null;

            while (true) {
                if (a == null && aIndex < aSection.size) {
                    aOffset = inA.getPosition();
                    a = read(inA, aIndexMap, aIndex);
                }
                if (b == null && bIndex < bSection.size) {
                    bOffset = inB.getPosition();
                    b = read(inB, bIndexMap, bIndex);
                }

                // Write the smaller of a and b. If they're equal, write only once
                boolean advanceA;
                boolean advanceB;
                if (a != null && b != null) {
                    int compare = a.compareTo(b);
                    advanceA = compare <= 0;
                    advanceB = compare >= 0;
                } else {
                    advanceA = (a != null);
                    advanceB = (b != null);
                }

                T toWrite = null;
                if (advanceA) {
                    toWrite = a;
                    updateIndex(aOffset, aIndexMap, aIndex++, outCount);
                    a = null;
                    aOffset = -1;
                }
                if (advanceB) {
                    toWrite = b;
                    updateIndex(bOffset, bIndexMap, bIndex++, outCount);
                    b = null;
                    bOffset = -1;
                }
                if (toWrite == null) {
                    break; // advanceA == false && advanceB == false
                }
                
                if (mergeType == 1 && classToRemove.contains(toWrite)) {
                    classIdToRemove.add(outCount);
                }
                if (mergeType == 2 && classIdToRemove.contains(toWrite)) {
                    typeIdToRemove.add(outCount);
                }
                
                write(toWrite);
                outCount++;
            }

            getSection(contentsOut).size = outCount;
        }

        /**
         * Merges unsorted sections by reading them completely into memory and
         * sorting in memory.
         */
        public final void mergeUnsorted() {
            getSection(contentsOut).off = out.getPosition();

            List<UnsortedValue> all = new ArrayList<UnsortedValue>();
            all.addAll(readUnsortedValues(dexA, aIndexMap));
            all.addAll(readUnsortedValues(dexB, bIndexMap));
            Collections.sort(all);

            int outCount = 0;
            for (int i = 0; i < all.size(); ) {
                UnsortedValue e1 = all.get(i++);
                updateIndex(e1.offset, getIndexMap(e1.source), e1.index, outCount - 1);

                while (i < all.size() && e1.compareTo(all.get(i)) == 0) {
                    UnsortedValue e2 = all.get(i++);
                    updateIndex(e2.offset, getIndexMap(e2.source), e2.index, outCount - 1);
                }

                write(e1.value);
                outCount++;
            }

            getSection(contentsOut).size = outCount;
        }

        private List<UnsortedValue> readUnsortedValues(DexBuffer source, IndexMap indexMap) {
            TableOfContents.Section section = getSection(source.getTableOfContents());
            if (!section.exists()) {
                return Collections.emptyList();
            }

            List<UnsortedValue> result = new ArrayList<UnsortedValue>();
            DexBuffer.Section in = source.open(section.off);
            for (int i = 0; i < section.size; i++) {
                int offset = in.getPosition();
                T value = read(in, indexMap, 0);
                result.add(new UnsortedValue(source, indexMap, value, i, offset));
            }
            return result;
        }

        abstract TableOfContents.Section getSection(TableOfContents tableOfContents);
        abstract T read(DexBuffer.Section in, IndexMap indexMap, int index);
        abstract void updateIndex(int offset, IndexMap indexMap, int oldIndex, int newIndex);
        abstract void write(T value);

        class UnsortedValue implements Comparable<UnsortedValue> {
            final DexBuffer source;
            final IndexMap indexMap;
            final T value;
            final int index;
            final int offset;

            UnsortedValue(DexBuffer source, IndexMap indexMap, T value, int index, int offset) {
                this.source = source;
                this.indexMap = indexMap;
                this.value = value;
                this.index = index;
                this.offset = offset;
            }

            public int compareTo(UnsortedValue unsortedValue) {
                return value.compareTo(unsortedValue.value);
            }
        }
    }

    private IndexMap getIndexMap(DexBuffer dexBuffer) {
        if (dexBuffer == dexA) {
            return aIndexMap;
        } else if (dexBuffer == dexB) {
            return bIndexMap;
        } else {
            throw new IllegalArgumentException();
        }
    }

    int mergeType = 0; // 1 for stringid, 2 for typeid
    public Set<String> classToRemove = new HashSet();
    private Set<Integer> classIdToRemove = new HashSet();
    private Set<Integer> typeIdToRemove = new HashSet();
    private void mergeStringIds() {
        classIdToRemove.clear();
        typeIdToRemove.clear();
        mergeType = 1;
        new IdMerger<String>(idsDefsOut) {
            @Override TableOfContents.Section getSection(TableOfContents tableOfContents) {
                return tableOfContents.stringIds;
            }

            @Override String read(DexBuffer.Section in, IndexMap indexMap, int index) {
                return in.readString();
            }

            @Override void updateIndex(int offset, IndexMap indexMap, int oldIndex, int newIndex) {
                indexMap.stringIds[oldIndex] = newIndex;
            }

            @Override void write(String value) {
                contentsOut.stringDatas.size++;
                idsDefsOut.writeInt(stringDataOut.getPosition());
                stringDataOut.writeStringData(value);
            }
        }.mergeSorted();
        mergeType = 0;
    }

    private void mergeTypeIds() {
        mergeType = 2;
        new IdMerger<Integer>(idsDefsOut) {
            @Override TableOfContents.Section getSection(TableOfContents tableOfContents) {
                return tableOfContents.typeIds;
            }

            @Override Integer read(DexBuffer.Section in, IndexMap indexMap, int index) {
                int stringIndex = in.readInt();
                return indexMap.adjustString(stringIndex);
            }

            @Override void updateIndex(int offset, IndexMap indexMap, int oldIndex, int newIndex) {
                if (newIndex < 0 || newIndex > 0xffff) {
                    throw new IllegalArgumentException("type ID not in [0, 0xffff]: " + newIndex);
                }
                indexMap.typeIds[oldIndex] = (short) newIndex;
            }

            @Override void write(Integer value) {
                idsDefsOut.writeInt(value);
            }
        }.mergeSorted();
        mergeType = 0;
    }

    private void mergeTypeLists() {
        new IdMerger<TypeList>(typeListOut) {
            @Override TableOfContents.Section getSection(TableOfContents tableOfContents) {
                return tableOfContents.typeLists;
            }

            @Override TypeList read(DexBuffer.Section in, IndexMap indexMap, int index) {
                return indexMap.adjustTypeList(in.readTypeList());
            }

            @Override void updateIndex(int offset, IndexMap indexMap, int oldIndex, int newIndex) {
                indexMap.putTypeListOffset(offset, typeListOut.getPosition());
            }

            @Override void write(TypeList value) {
                typeListOut.writeTypeList(value);
            }
        }.mergeUnsorted();
    }

    static class AnnotationDirectory implements Comparable<AnnotationDirectory> {

        int cmp(List<int[]> a, List<int[]> b) {
            if (a.size() == b.size()) {
                for (int i = 0; i < a.size(); i++) {
                    int aE[] = a.get(i);
                    int bE[] = b.get(i);
                    if (aE.length == bE.length) {
                        for (int j = 0; j < aE.length; j++) {
                            if (aE[j] != bE[j]) {
                                return aE[i] - bE[j];
                            }
                        }
                    } else {
                        return aE.length - bE.length;
                    }
                }
                return 0;
            } else {
                return a.size() - b.size();
            }
        }

        int clazz;
        List<int[]> methods = new ArrayList<int[]>();
        List<int[]> fields = new ArrayList<int[]>();
        List<int[]> parameters = new ArrayList<int[]>();

        @Override
        public int compareTo(AnnotationDirectory o) {
            if (clazz != o.clazz) {
                return clazz - o.clazz;
            } else {
                int x = cmp(this.methods, o.methods);
                if (x != 0) {
                    return x;
                }
                x = cmp(this.fields, o.fields);
                if (x != 0) {
                    return x;
                }
                x = cmp(this.parameters, o.parameters);
                return x;
            }
        }
    }
    static class IntArray implements Comparable<IntArray> {
        final public int values[];

        public IntArray(int[] annotations) {
            super();
            this.values = annotations;
        }

        public String toString() {
            return values.length + " " + Arrays.toString(values);
        }

        @Override
        public int compareTo(IntArray o) {
            if (values.length == o.values.length) {
                for (int i = 0; i < values.length; i++) {
                    if (values[i] != o.values[i]) {
                        return values[i] - o.values[i];
                    }
                }
                return 0;
            } else {
                return values.length - o.values.length;
            }
        }
    }

    private void mergeAnnotationSets() {
        annotationSetOut.assertFourByteAligned();
        new IdMerger<IntArray>(annotationSetOut) {

            @Override
            Section getSection(TableOfContents tableOfContents) {
                return tableOfContents.annotationSets;
            }

            @Override
            IntArray read(com.android.dx.io.DexBuffer.Section in, IndexMap indexMap, int index) {
                int size = in.readInt();
                int annotations[] = new int[size];
                for (int i = 0; i < size; i++) {
                    annotations[i] = indexMap.adjustAnnotation(in.readInt());
                }
                return new IntArray(annotations);
            }

            @Override
            void updateIndex(int offset, IndexMap indexMap, int oldIndex, int newIndex) {
                indexMap.putAnnotationSetOffset(offset, annotationSetOut.getPosition());
            }

            @Override
            void write(IntArray value) {
                int size = value.values.length;
                annotationSetOut.writeInt(size);
                for (int j = 0; j < size; j++) {
                    annotationSetOut.writeInt(value.values[j]);
                }
                contentsOut.annotationSets.size++;
            }
        }.mergeUnsorted();
    }

    private void mergeAnnotationSetRefs() {
        annotationSetRefListOut.assertFourByteAligned();
        new IdMerger<IntArray>(annotationSetRefListOut) {

            @Override
            Section getSection(TableOfContents tableOfContents) {
                return tableOfContents.annotationSetRefLists;
            }

            @Override
            IntArray read(com.android.dx.io.DexBuffer.Section in, IndexMap indexMap, int index) {
                int size = in.readInt();
                int annotations[] = new int[size];
                for (int i = 0; i < size; i++) {
                    annotations[i] = indexMap.adjustAnnotationSet(in.readInt());
                }
                return new IntArray(annotations);
            }

            @Override
            void updateIndex(int offset, IndexMap indexMap, int oldIndex, int newIndex) {
                indexMap.putAnnotationSetRefOffset(offset, annotationSetRefListOut.getPosition());
            }

            @Override
            void write(IntArray value) {
                int size = value.values.length;
                annotationSetRefListOut.writeInt(size);
                for (int j = 0; j < size; j++) {
                    annotationSetRefListOut.writeInt(value.values[j]);
                }
                contentsOut.annotationSetRefLists.size++;
            }
        }.mergeUnsorted();
    }
    
    private void mergeProtoIds() {
        new IdMerger<ProtoId>(idsDefsOut) {
            @Override TableOfContents.Section getSection(TableOfContents tableOfContents) {
                return tableOfContents.protoIds;
            }

            @Override ProtoId read(DexBuffer.Section in, IndexMap indexMap, int index) {
                return indexMap.adjust(in.readProtoId());
            }

            @Override void updateIndex(int offset, IndexMap indexMap, int oldIndex, int newIndex) {
                if (newIndex < 0 || newIndex > 0xffff) {
                    throw new IllegalArgumentException("proto ID not in [0, 0xffff]: " + newIndex);
                }
                indexMap.protoIds[oldIndex] = (short) newIndex;
            }

            @Override void write(ProtoId value) {
                value.writeTo(idsDefsOut);
            }
        }.mergeSorted();
    }

    private void mergeFieldIds() {
        new IdMerger<FieldId>(idsDefsOut) {
            @Override TableOfContents.Section getSection(TableOfContents tableOfContents) {
                return tableOfContents.fieldIds;
            }

            @Override FieldId read(DexBuffer.Section in, IndexMap indexMap, int index) {
                return indexMap.adjust(in.readFieldId());
            }

            @Override void updateIndex(int offset, IndexMap indexMap, int oldIndex, int newIndex) {
                if (newIndex < 0 || newIndex > 0xffff) {
                    throw new IllegalArgumentException("field ID not in [0, 0xffff]: " + newIndex);
                }
                indexMap.fieldIds[oldIndex] = (short) newIndex;
            }

            @Override void write(FieldId value) {
                value.writeTo(idsDefsOut);
            }
        }.mergeSorted();
    }

    private void mergeMethodIds() {
        new IdMerger<MethodId>(idsDefsOut) {
            @Override TableOfContents.Section getSection(TableOfContents tableOfContents) {
                return tableOfContents.methodIds;
            }

            @Override MethodId read(DexBuffer.Section in, IndexMap indexMap, int index) {
                return indexMap.adjust(in.readMethodId());
            }

            @Override void updateIndex(int offset, IndexMap indexMap, int oldIndex, int newIndex) {
                if (newIndex < 0 || newIndex > 0xffff) {
                    throw new IllegalArgumentException("method ID not in [0, 0xffff]: " + newIndex);
                }
                indexMap.methodIds[oldIndex] = (short) newIndex;
            }

            @Override void write(MethodId methodId) {
                methodId.writeTo(idsDefsOut);
            }
        }.mergeSorted();
    }

    private void mergeAnnotationDirectory() {
        new IdMerger<AnnotationDirectory>(annotationsDirectoryOut) {

            @Override
            Section getSection(TableOfContents tableOfContents) {
                return tableOfContents.annotationsDirectories;
            }

            @Override
            AnnotationDirectory read(com.android.dx.io.DexBuffer.Section in, IndexMap indexMap, int index) {
                AnnotationDirectory ad = new AnnotationDirectory();
                ad.clazz = indexMap.adjustAnnotationSet(in.readInt());
                int fieldSize = in.readInt();
                int methodSize = in.readInt();
                int parameterSize = in.readInt();
                for (int i = 0; i < fieldSize; i++) {
                    ad.fields.add(new int[] { indexMap.adjustField(in.readInt()),
                            indexMap.adjustAnnotationSet(in.readInt()) });
                }
                for (int i = 0; i < methodSize; i++) {
                    ad.methods.add(new int[] { indexMap.adjustMethod(in.readInt()),
                            indexMap.adjustAnnotationSet(in.readInt()) });
                }
                for (int i = 0; i < parameterSize; i++) {
                    ad.parameters.add(new int[] { indexMap.adjustMethod(in.readInt()),
                            indexMap.adjustAnnotationSetRef(in.readInt()) });
                }
                return ad;
            }

            @Override
            void updateIndex(int offset, IndexMap indexMap, int oldIndex, int newIndex) {
                indexMap.putAnnotationDirectoryOffset(offset, annotationsDirectoryOut.getPosition());
            }

            @Override
            void write(AnnotationDirectory value) {
                contentsOut.annotationsDirectories.size++;
                annotationsDirectoryOut.writeInt(value.clazz);
                annotationsDirectoryOut.writeInt(value.fields.size());
                annotationsDirectoryOut.writeInt(value.methods.size());
                annotationsDirectoryOut.writeInt(value.parameters.size());
                for (int[] a : value.fields) {
                    annotationsDirectoryOut.writeInt(a[0]);
                    annotationsDirectoryOut.writeInt(a[1]);
                }
                for (int[] a : value.methods) {
                    annotationsDirectoryOut.writeInt(a[0]);
                    annotationsDirectoryOut.writeInt(a[1]);
                }
                for (int[] a : value.parameters) {
                    annotationsDirectoryOut.writeInt(a[0]);
                    annotationsDirectoryOut.writeInt(a[1]);
                }
            }
        }.mergeUnsorted();
    }
    private void mergeAnnotations() {
        new IdMerger<Annotation>(annotationOut) {
            @Override TableOfContents.Section getSection(TableOfContents tableOfContents) {
                return tableOfContents.annotations;
            }

            @Override Annotation read(DexBuffer.Section in, IndexMap indexMap, int index) {
                return indexMap.adjust(in.readAnnotation());
            }

            @Override void updateIndex(int offset, IndexMap indexMap, int oldIndex, int newIndex) {
                indexMap.putAnnotationOffset(offset, annotationOut.getPosition());
            }

            @Override void write(Annotation value) {
                value.writeTo(annotationOut);
            }
        }.mergeUnsorted();
    }

    private void mergeClassDefs() {
        SortableType[] types = getSortedTypes();
        contentsOut.classDefs.off = idsDefsOut.getPosition();
        contentsOut.classDefs.size = types.length;

        for (SortableType type : types) {
            DexBuffer in = type.getBuffer();
            IndexMap indexMap = (in == dexA) ? aIndexMap : bIndexMap;
            transformClassDef(in, type.getClassDef(), indexMap);
        }
    }

    /**
     * Returns the union of classes from both files, sorted in order such that
     * a class is always preceded by its supertype and implemented interfaces.
     */
    private SortableType[] getSortedTypes() {
        // size is pessimistic; doesn't include arrays
        SortableType[] sortableTypes = new SortableType[contentsOut.typeIds.size];
        readSortableTypes(sortableTypes, dexA, aIndexMap);
        readSortableTypes(sortableTypes, dexB, bIndexMap);

        
        for (int i = 0; i < sortableTypes.length; i++) {
            SortableType sortableType = sortableTypes[i];
            if (sortableType != null) {
                if (typeIdToRemove.contains(sortableType.getClassDef()
                        .getTypeIndex())) {
                    sortableTypes[i] = null;
                }
            }
        }
        
        /*
         * Populate the depths of each sortable type. This makes D iterations
         * through all N types, where 'D' is the depth of the deepest type. For
         * example, the deepest class in libcore is Xalan's KeyIterator, which
         * is 11 types deep.
         */
        while (true) {
            boolean allDone = true;
            for (SortableType sortableType : sortableTypes) {
                if (sortableType != null && !sortableType.isDepthAssigned()) {
                    allDone &= sortableType.tryAssignDepth(sortableTypes);
                }
            }
            if (allDone) {
                break;
            }
        }

        // Now that all types have depth information, the result can be sorted
        Arrays.sort(sortableTypes, SortableType.NULLS_LAST_ORDER);

        // Strip nulls from the end
        int firstNull = Arrays.asList(sortableTypes).indexOf(null);
        return firstNull != -1
                ? Arrays.copyOfRange(sortableTypes, 0, firstNull)
                : sortableTypes;
    }

    /**
     * Reads just enough data on each class so that we can sort it and then find
     * it later.
     */
    private void readSortableTypes(SortableType[] sortableTypes, DexBuffer buffer,
            IndexMap indexMap) {
        for (ClassDef classDef : buffer.classDefs()) {
            SortableType sortableType = indexMap.adjust(new SortableType(buffer, classDef));
            int t = sortableType.getTypeIndex();
            if (sortableTypes[t] == null) {
                sortableTypes[t] = sortableType;
            } else if (collisionPolicy != CollisionPolicy.KEEP_FIRST) {
                throw new DexException("Multiple dex files define "
                        + buffer.typeNames().get(classDef.getTypeIndex()));
            }
        }
    }

    /**
     * Reads a class_def_item beginning at {@code in} and writes the index and
     * data.
     */
    private void transformClassDef(DexBuffer in, ClassDef classDef, IndexMap indexMap) {
        idsDefsOut.assertFourByteAligned();
        idsDefsOut.writeInt(classDef.getTypeIndex());
        idsDefsOut.writeInt(classDef.getAccessFlags());
        idsDefsOut.writeInt(classDef.getSupertypeIndex());
        idsDefsOut.writeInt(classDef.getInterfacesOffset());

        int sourceFileIndex = indexMap.adjustString(classDef.getSourceFileIndex());
        idsDefsOut.writeInt(sourceFileIndex);

        int annotationsOff = classDef.getAnnotationsOffset();
        idsDefsOut.writeInt(indexMap.adjustAnnotationDirectory(annotationsOff));

        int classDataOff = classDef.getClassDataOffset();
        if (classDataOff == 0) {
            idsDefsOut.writeInt(0);
        } else {
            idsDefsOut.writeInt(classDataOut.getPosition());
            ClassData classData = in.readClassData(classDef);
            transformClassData(in, classData, indexMap);
        }

        int staticValuesOff = classDef.getStaticValuesOffset();
        idsDefsOut.writeInt(indexMap.adjustStaticValues(staticValuesOff));
    }

    private void transformClassData(DexBuffer in, ClassData classData, IndexMap indexMap) {
        contentsOut.classDatas.size++;

        ClassData.Field[] staticFields = classData.getStaticFields();
        ClassData.Field[] instanceFields = classData.getInstanceFields();
        ClassData.Method[] directMethods = classData.getDirectMethods();
        ClassData.Method[] virtualMethods = classData.getVirtualMethods();

        classDataOut.writeUleb128(staticFields.length);
        classDataOut.writeUleb128(instanceFields.length);
        classDataOut.writeUleb128(directMethods.length);
        classDataOut.writeUleb128(virtualMethods.length);

        transformFields(indexMap, staticFields);
        transformFields(indexMap, instanceFields);
        transformMethods(in, indexMap, directMethods);
        transformMethods(in, indexMap, virtualMethods);
    }

    private void transformFields(IndexMap indexMap, ClassData.Field[] fields) {
        int lastOutFieldIndex = 0;
        for (ClassData.Field field : fields) {
            int outFieldIndex = indexMap.adjustField(field.getFieldIndex());
            classDataOut.writeUleb128(outFieldIndex - lastOutFieldIndex);
            lastOutFieldIndex = outFieldIndex;
            classDataOut.writeUleb128(field.getAccessFlags());
        }
    }

    private void transformMethods(DexBuffer in, IndexMap indexMap, ClassData.Method[] methods) {
        int lastOutMethodIndex = 0;
        for (ClassData.Method method : methods) {
            int outMethodIndex = indexMap.adjustMethod(method.getMethodIndex());
            classDataOut.writeUleb128(outMethodIndex - lastOutMethodIndex);
            lastOutMethodIndex = outMethodIndex;

            classDataOut.writeUleb128(method.getAccessFlags());

            if (method.getCodeOffset() == 0) {
                classDataOut.writeUleb128(0);
            } else {
                codeOut.alignToFourBytes();
                classDataOut.writeUleb128(codeOut.getPosition());
                transformCode(in, in.readCode(method), indexMap);
            }
        }
    }

    private void transformCode(DexBuffer in, Code code, IndexMap indexMap) {
        contentsOut.codes.size++;
        codeOut.assertFourByteAligned();

        codeOut.writeUnsignedShort(code.getRegistersSize());
        codeOut.writeUnsignedShort(code.getInsSize());
        codeOut.writeUnsignedShort(code.getOutsSize());

        Code.Try[] tries = code.getTries();
        Code.CatchHandler[] catchHandlers = code.getCatchHandlers();
        codeOut.writeUnsignedShort(tries.length);

        int debugInfoOffset = code.getDebugInfoOffset();
        if (debugInfoOffset != 0) {
            codeOut.writeInt(debugInfoOut.getPosition());
            transformDebugInfoItem(in.open(debugInfoOffset), indexMap);
        } else {
            codeOut.writeInt(0);
        }

        short[] instructions = code.getInstructions();
        InstructionTransformer transformer = (in == dexA)
                ? aInstructionTransformer
                : bInstructionTransformer;
        short[] newInstructions = transformer.transform(instructions);
        codeOut.writeInt(newInstructions.length);
        codeOut.write(newInstructions);

        if (tries.length > 0) {
            if (newInstructions.length % 2 == 1) {
                codeOut.writeShort((short) 0); // padding
            }

            /*
             * We can't write the tries until we've written the catch handlers.
             * Unfortunately they're in the opposite order in the dex file so we
             * need to transform them out-of-order.
             */
            DexBuffer.Section triesSection = dexOut.open(codeOut.getPosition());
            codeOut.skip(tries.length * SizeOf.TRY_ITEM);
            int[] offsets = transformCatchHandlers(indexMap, catchHandlers);
            transformTries(triesSection, tries, offsets);
        }
    }

    /**
     * Writes the catch handlers to {@code codeOut} and returns their indices.
     */
    private int[] transformCatchHandlers(IndexMap indexMap, Code.CatchHandler[] catchHandlers) {
        int baseOffset = codeOut.getPosition();
        codeOut.writeUleb128(catchHandlers.length);
        int[] offsets = new int[catchHandlers.length];
        for (int i = 0; i < catchHandlers.length; i++) {
            offsets[i] = codeOut.getPosition() - baseOffset;
            transformEncodedCatchHandler(catchHandlers[i], indexMap);
        }
        return offsets;
    }

    private void transformTries(DexBuffer.Section out, Code.Try[] tries,
            int[] catchHandlerOffsets) {
        for (Code.Try tryItem : tries) {
            out.writeInt(tryItem.getStartAddress());
            out.writeUnsignedShort(tryItem.getInstructionCount());
            out.writeUnsignedShort(catchHandlerOffsets[tryItem.getCatchHandlerIndex()]);
        }
    }

    private static final byte DBG_END_SEQUENCE = 0x00;
    private static final byte DBG_ADVANCE_PC = 0x01;
    private static final byte DBG_ADVANCE_LINE = 0x02;
    private static final byte DBG_START_LOCAL = 0x03;
    private static final byte DBG_START_LOCAL_EXTENDED = 0x04;
    private static final byte DBG_END_LOCAL = 0x05;
    private static final byte DBG_RESTART_LOCAL = 0x06;
    private static final byte DBG_SET_PROLOGUE_END = 0x07;
    private static final byte DBG_SET_EPILOGUE_BEGIN = 0x08;
    private static final byte DBG_SET_FILE = 0x09;

    private void transformDebugInfoItem(DexBuffer.Section in, IndexMap indexMap) {
        contentsOut.debugInfos.size++;
        int lineStart = in.readUleb128();
        debugInfoOut.writeUleb128(lineStart);

        int parametersSize = in.readUleb128();
        debugInfoOut.writeUleb128(parametersSize);

        for (int p = 0; p < parametersSize; p++) {
            int parameterName = in.readUleb128p1();
            debugInfoOut.writeUleb128p1(indexMap.adjustString(parameterName));
        }

        int addrDiff;    // uleb128   address delta.
        int lineDiff;    // sleb128   line delta.
        int registerNum; // uleb128   register number.
        int nameIndex;   // uleb128p1 string index.    Needs indexMap adjustment.
        int typeIndex;   // uleb128p1 type index.      Needs indexMap adjustment.
        int sigIndex;    // uleb128p1 string index.    Needs indexMap adjustment.

        while (true) {
            int opcode = in.readByte();
            debugInfoOut.writeByte(opcode);

            switch (opcode) {
            case DBG_END_SEQUENCE:
                return;

            case DBG_ADVANCE_PC:
                addrDiff = in.readUleb128();
                debugInfoOut.writeUleb128(addrDiff);
                break;

            case DBG_ADVANCE_LINE:
                lineDiff = in.readSleb128();
                debugInfoOut.writeSleb128(lineDiff);
                break;

            case DBG_START_LOCAL:
            case DBG_START_LOCAL_EXTENDED:
                registerNum = in.readUleb128();
                debugInfoOut.writeUleb128(registerNum);
                nameIndex = in.readUleb128p1();
                debugInfoOut.writeUleb128p1(indexMap.adjustString(nameIndex));
                typeIndex = in.readUleb128p1();
                debugInfoOut.writeUleb128p1(indexMap.adjustType(typeIndex));
                if (opcode == DBG_START_LOCAL_EXTENDED) {
                    sigIndex = in.readUleb128p1();
                    debugInfoOut.writeUleb128p1(indexMap.adjustString(sigIndex));
                }
                break;

            case DBG_END_LOCAL:
            case DBG_RESTART_LOCAL:
                registerNum = in.readUleb128();
                debugInfoOut.writeUleb128(registerNum);
                break;

            case DBG_SET_FILE:
                nameIndex = in.readUleb128p1();
                debugInfoOut.writeUleb128p1(indexMap.adjustString(nameIndex));
                break;

            case DBG_SET_PROLOGUE_END:
            case DBG_SET_EPILOGUE_BEGIN:
            default:
                break;
            }
        }
    }

    private void transformEncodedCatchHandler(Code.CatchHandler catchHandler, IndexMap indexMap) {
        int catchAllAddress = catchHandler.getCatchAllAddress();
        int[] typeIndexes = catchHandler.getTypeIndexes();
        int[] addresses = catchHandler.getAddresses();

        if (catchAllAddress != -1) {
            codeOut.writeSleb128(-typeIndexes.length);
        } else {
            codeOut.writeSleb128(typeIndexes.length);
        }

        for (int i = 0; i < typeIndexes.length; i++) {
            codeOut.writeUleb128(indexMap.adjustType(typeIndexes[i]));
            codeOut.writeUleb128(addresses[i]);
        }

        if (catchAllAddress != -1) {
            codeOut.writeUleb128(catchAllAddress);
        }
    }

    private void mergeStaticValue() {
        new IdMerger<EncodedValue>(encodedArrayOut) {

            @Override
            Section getSection(TableOfContents tableOfContents) {
                return tableOfContents.encodedArrays;
            }

            @Override
            EncodedValue read(com.android.dx.io.DexBuffer.Section in, IndexMap indexMap, int index) {
                return indexMap.adjustEncodedArray(in.readEncodedArray());
            }

            @Override
            void updateIndex(int offset, IndexMap indexMap, int oldIndex, int newIndex) {
                indexMap.putStaticValuesOffset(offset, encodedArrayOut.getPosition());
            }

            @Override
            void write(EncodedValue value) {
                contentsOut.encodedArrays.size++;
                value.writeTo(encodedArrayOut);
            }
        }.mergeUnsorted();
    }

    /**
     * Byte counts for the sections written when creating a dex. Target sizes
     * are defined in one of two ways:
     * <ul>
     * <li>By pessimistically guessing how large the union of dex files will be.
     *     We're pessimistic because we can't predict the amount of duplication
     *     between dex files, nor can we predict the length of ULEB-encoded
     *     offsets or indices.
     * <li>By exactly measuring an existing dex.
     * </ul>
     */
    private static class WriterSizes {
        private int header = SizeOf.HEADER_ITEM;
        private int idsDefs;
        private int mapList;
        private int typeList;
        private int classData;
        private int code;
        private int stringData;
        private int debugInfo;
        private int encodedArray;
        private int annotationsDirectory;
        private int annotationsSet;
        private int annotationsSetRefList;
        private int annotation;

        /**
         * Compute sizes for merging a and b.
         */
        public WriterSizes(DexBuffer a, DexBuffer b) {
            plus(a.getTableOfContents(), false);
            plus(b.getTableOfContents(), false);
        }

        public WriterSizes(DexMerger dexMerger) {
            header = dexMerger.headerOut.used();
            idsDefs = dexMerger.idsDefsOut.used();
            mapList = dexMerger.mapListOut.used();
            typeList = dexMerger.typeListOut.used();
            classData = dexMerger.classDataOut.used();
            code = dexMerger.codeOut.used();
            stringData = dexMerger.stringDataOut.used();
            debugInfo = dexMerger.debugInfoOut.used();
            encodedArray = dexMerger.encodedArrayOut.used();
            annotationsDirectory = dexMerger.annotationsDirectoryOut.used();
            annotationsSet = dexMerger.annotationSetOut.used();
            annotationsSetRefList = dexMerger.annotationSetRefListOut.used();
            annotation = dexMerger.annotationOut.used();
        }

        public void plus(TableOfContents contents, boolean exact) {
            idsDefs += contents.stringIds.size * SizeOf.STRING_ID_ITEM
                    + contents.typeIds.size * SizeOf.TYPE_ID_ITEM
                    + contents.protoIds.size * SizeOf.PROTO_ID_ITEM
                    + contents.fieldIds.size * SizeOf.MEMBER_ID_ITEM
                    + contents.methodIds.size * SizeOf.MEMBER_ID_ITEM
                    + contents.classDefs.size * SizeOf.CLASS_DEF_ITEM;
            mapList = SizeOf.UINT + (contents.sections.length * SizeOf.MAP_ITEM);
            typeList += contents.typeLists.byteCount;
            stringData += contents.stringDatas.byteCount;
            annotationsDirectory += contents.annotationsDirectories.byteCount;
            annotationsSet += contents.annotationSets.byteCount;
            annotationsSetRefList += contents.annotationSetRefLists.byteCount;

            if (exact) {
                code += contents.codes.byteCount;
                classData += contents.classDatas.byteCount;
                encodedArray += contents.encodedArrays.byteCount;
                annotation += contents.annotations.byteCount;
                debugInfo += contents.debugInfos.byteCount;
            } else {
                // at most 1/4 of the bytes in a code section are uleb/sleb
                code += (int) Math.ceil(contents.codes.byteCount * 1.25);
                // at most 1/3 of the bytes in a class data section are uleb/sleb
                classData += (int) Math.ceil(contents.classDatas.byteCount * 1.34);
                // all of the bytes in an encoding arrays section may be uleb/sleb
                encodedArray += contents.encodedArrays.byteCount * 2;
                // all of the bytes in an annotations section may be uleb/sleb
                annotation += (int) Math.ceil(contents.annotations.byteCount * 2);
                // all of the bytes in a debug info section may be uleb/sleb
                debugInfo += contents.debugInfos.byteCount * 2;
            }

            typeList = DexBuffer.fourByteAlign(typeList);
            code = DexBuffer.fourByteAlign(code);
        }

        public int size() {
            return header + idsDefs + mapList + typeList + classData + code + stringData + debugInfo
                    + encodedArray + annotationsDirectory + annotationsSet + annotationsSetRefList
                    + annotation;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            printUsage();
            return;
        }

        DexBuffer dexA = new DexBuffer(new File(args[1]));
        DexBuffer dexB = new DexBuffer(new File(args[2]));
        DexMerger dexMerger = new DexMerger(dexA, dexB, CollisionPolicy.KEEP_FIRST);
        dexMerger.classToRemove.add("Ltest/Type1;");
        dexMerger.classToRemove.add("Ltest/Type2;");
        DexBuffer merged = dexMerger.merge();
        merged.writeTo(new File(args[0]));
    }

    private static void printUsage() {
        System.out.println("Usage: DexMerger <out.dex> <a.dex> <b.dex>");
        System.out.println();
        System.out.println("If both a and b define the same classes, a's copy will be used.");
    }
}

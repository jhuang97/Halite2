package hlt;

import java.util.Arrays;

public class DisjointSet {

	int size;
	int[] parent;
	int[] rank;
	
	public static DisjointSet makeSingletons(int size) {
		DisjointSet ds = new DisjointSet();
		ds.size = size;
		ds.parent = new int[size];
		ds.rank = new int[size];
		for (int i = 0; i < size; i++) ds.makeSet(i);
		return ds;
	}
	
	public void makeSet(int x) {
		parent[x] = x;
		rank[x] = 0;
	}
	
	public int find(int x) {
		if (parent[x] != x) parent[x] = find(parent[x]);
		return parent[x];
	}
	
	public void union(int x, int y) {
		int xRoot = find(x);
		int yRoot = find(y);
		if (xRoot == yRoot) return;
		
		if (rank[xRoot] < rank[yRoot]) parent[xRoot] = yRoot;
		else if (rank[xRoot] > rank[yRoot]) parent[yRoot] = xRoot;
		else {
			parent[yRoot] = xRoot;
			rank[xRoot]++;
		}
	}
	
	@Override
	public String toString() {
		return Arrays.toString(parent) + ", " + Arrays.toString(rank);
	}
	
	public static void main(String[] args) {
		DisjointSet ds = makeSingletons(5);
		System.out.println(ds);
		ds.union(0, 4);
		System.out.println(ds);
		ds.union(2, 3);
		System.out.println(ds);
		ds.union(4, 2);
		System.out.println(ds);
		ds.find(3);
		System.out.println(ds);
	}

}

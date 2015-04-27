package water.api;

import hex.Model;
import water.*;
import water.api.ModelsHandler.Models;
import water.exceptions.*;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.Vec;
import water.persist.PersistManager;
import water.util.FileUtils;
import water.util.Log;

import java.io.*;
import java.util.*;

class FramesHandler<I extends FramesHandler.Frames, S extends FramesBase<I, S>> extends Handler {

  /** Class which contains the internal representation of the frames list and params. */
  protected static final class Frames extends Iced {
    Key frame_id;
    long offset;
    int len;
    Frame[] frames;
    String column;
    public boolean find_compatible_models = false;

    /**
     * Fetch all Frames from the KV store.
     */
    protected static Frame[] fetchAll() {
      // Get all the frames.
      final Key[] frameKeys = KeySnapshot.globalKeysOfClass(Frame.class);
      Frame[] frames = new Frame[frameKeys.length];
      for (int i = 0; i < frameKeys.length; i++) {
        Frame frame = getFromDKV("(none)", frameKeys[i]);
        frames[i] = frame;
      }
      return frames;
    }

    /**
     * Fetch all the Models so we can see if they are compatible with our Frame(s).
     */
    static protected Map<Model, Set<String>> fetchModelCols(Model[] all_models) {
      Map<Model, Set<String>> all_models_cols = new HashMap<>();
      for (Model m : all_models)
        all_models_cols.put(m, new HashSet<>(Arrays.asList(m._output._names)));
      return all_models_cols;
    }

    /**
     * For a given frame return an array of the compatible models.
     *
     * @param frame The frame for which we should fetch the compatible models.
     * @param all_models An array of all the Models in the DKV.
     * @return
     */
    private static Model[] findCompatibleModels(Frame frame, Model[] all_models) {
      Map<Model, Set<String>> all_models_cols = Frames.fetchModelCols(all_models);
      List<Model> compatible_models = new ArrayList<Model>();

      Set<String> frame_column_names = new HashSet(Arrays.asList(frame._names));

      for (Map.Entry<Model, Set<String>> entry : all_models_cols.entrySet()) {
        Model model = entry.getKey();
        Set<String> model_cols = entry.getValue();

        if (frame_column_names.containsAll(model_cols)) {
          // See if adapt throws an exception or not.
          try {
            if( model.adaptTestForTrain(new Frame(frame), false).length == 0 )
              compatible_models.add(model);
          } catch( IllegalArgumentException e ) {
            // skip
          }
        }
      }
      return compatible_models.toArray(new Model[0]);
    }
  }

  /** Return all the frames. */
  @SuppressWarnings("unused") // called through reflection by RequestServer
  public FramesV3 list(int version, FramesV3 s) {
    Frames f = s.createAndFillImpl();
    f.frames = Frames.fetchAll();

    s.fillFromImpl(f);

    // Summary data is big, and not always there: null it out here.  You have to call columnSummary
    // to force computation of the summary data.
    for (FrameV3 a_frame: s.frames) {
      a_frame.clearBinsField();
    }

    return s;
  }

  /** NOTE: We really want to return a different schema here! */
  @SuppressWarnings("unused") // called through reflection by RequestServer
  public FramesV3 columns(int version, FramesV3 s) {
    // TODO: return *only* the columns. . .  This may be a different schema.
    return fetch(version, s);
  }

  // TODO: almost identical to ModelsHandler; refactor
  public static Frame getFromDKV(String param_name, String key_str) {
    return getFromDKV(param_name, Key.make(key_str));
  }

  // TODO: almost identical to ModelsHandler; refactor
  public static Frame getFromDKV(String param_name, Key key) {
    if (null == key)
      throw new H2OIllegalArgumentException(param_name, "Frames.getFromDKV()", key);

    Value v = DKV.get(key);
    if (null == v)
      throw new H2OKeyNotFoundArgumentException(param_name, key.toString());

    Iced ice = v.get();
    if( ice instanceof Vec )
      return new Frame((Vec)ice);

    if (! (ice instanceof Frame))
      throw new H2OKeyWrongTypeArgumentException(param_name, key.toString(), Frame.class, ice.getClass());

    return (Frame)ice;
  }

  /** Return a single column from the frame. */
  @SuppressWarnings("unused") // called through reflection by RequestServer
  public FramesV3 column(int version, FramesV3 s) { // TODO: should return a Vec schema
    Frame frame = getFromDKV("key", s.frame_id.key());

    Vec vec = frame.vec(s.column);
    if (null == vec)
      throw new H2OColumnNotFoundArgumentException("column", s.frame_id.toString(), s.column);

    Vec[] vecs = { vec };
    String[] names = { s.column };
    Frame new_frame = new Frame(names, vecs);
    s.frames = new FrameV3[1];
    s.frames[0] = new FrameV3().fillFromImpl(new_frame);
    s.frames[0].clearBinsField();
    return s;
  }

  @SuppressWarnings("unused") // called through reflection by RequestServer
  public FramesV3 columnDomain(int version, FramesV3 s) {
    Frame frame = getFromDKV("key", s.frame_id.key());
    Vec vec = frame.vec(s.column);
    if (vec == null)
      throw new H2OColumnNotFoundArgumentException("column", s.frame_id.toString(), s.column);
    s.domain = new String[1][];
    s.domain[0] = vec.domain();
    return s;
  }

  @SuppressWarnings("unused") // called through reflection by RequestServer
  public FramesV3 columnSummary(int version, FramesV3 s) {
    Frame frame = getFromDKV("key", s.frame_id.key()); // safe
    Vec vec = frame.vec(s.column);
    if (null == vec)
      throw new H2OColumnNotFoundArgumentException("column", s.frame_id.toString(), s.column);

    // Compute second pass of rollups: the histograms.
    if (!vec.isString()) {
      vec.bins();
    }

    // Cons up our result
    s.frames = new FrameV3[1];
    s.frames[0] = new FrameV3().fillFromImpl(new Frame(new String[]{s.column}, new Vec[]{vec}), s.row_offset, s.row_count, true);
    return s;
  }

  /** Docs for column summary. */
  public StringBuffer columnSummaryDocs(int version, StringBuffer docs) {
    return null; // doc(this, version, docs, "docs/columnSummary.md");
  }

  public static class ChunkHomesEntryV3 extends Schema<Iced, ChunkHomesEntryV3> {
    @API(help="IP and Port of node", direction = API.Direction.OUTPUT)
    public String ip_port;

    @API(help="Number of chunks stored in this node for one of the vectors in the frame", direction = API.Direction.OUTPUT)
    long num_chunks_per_vec;

    @API(help="Number chunks stored in this node for the frame (num_chunks_per_vec * num_vecs)", direction = API.Direction.OUTPUT)
    long num_chunks;

    @API(help="Number of rows stored in this node", direction = API.Direction.OUTPUT)
    long num_rows;
  }

  public static class ChunkHomesV3 extends Schema<Iced, ChunkHomesV3> {
    @API(help="Number vecs (columns) stored in the frame", direction = API.Direction.OUTPUT)
    long num_vecs;

    @API(help="Array of nodes in the cluster", direction = API.Direction.OUTPUT)
    public ChunkHomesEntryV3[] entries;
  }

  /** Return chunk home information. */
  @SuppressWarnings("unused") // called through reflection by RequestServer
  public ChunkHomesV3 chunkHomes(int version, FramesV3 s) {
    ChunkHomesV3 h = new ChunkHomesV3();
    h.entries = new ChunkHomesEntryV3[H2O.CLOUD.size()];
    for (int i = 0; i < h.entries.length; i++) {
      H2ONode n = H2O.CLOUD.members()[i];
      h.entries[i] = new ChunkHomesEntryV3();
      h.entries[i].ip_port = n.getIpPortString();
    }

    Frame frame = getFromDKV("key", s.key.key()); // safe
    int numVecs = frame.vecs().length;
    h.num_vecs = numVecs;
    Vec any = frame.anyVec();
    int n = any.nChunks();
    for (int i = 0; i < n; i++) {
      Key k = any.chunkKey(i);
      int node_idx = k.home_node().index();
      h.entries[node_idx].num_chunks_per_vec += 1;
      h.entries[node_idx].num_chunks += numVecs;
      h.entries[node_idx].num_rows += any._espc[i + 1] - any._espc[i];
    }

    return h;
  }

  /** Return a single frame. */
  @SuppressWarnings("unused") // called through reflection by RequestServer
  public FramesV3 fetch(int version, FramesV3 s) {
    FramesV3 frames = doFetch(version, s, FrameV3.ColV2.NO_SUMMARY);

    // Summary data is big, and not always there: null it out here.  You have to call columnSummary
    // to force computation of the summary data.
    for (FrameV3 a_frame: frames.frames) {
      a_frame.clearBinsField();
    }

    return frames;
  }

  private FramesV3 doFetch(int version, FramesV3 s, boolean force_summary) {
    Frames f = s.createAndFillImpl();

    Frame frame = getFromDKV("key", s.frame_id.key()); // safe
    s.frames = new FrameV3[1];
    s.frames[0] = new FrameV3(frame, s.row_offset, s.row_count).fillFromImpl(frame, s.row_offset, s.row_count, force_summary);  // TODO: Refactor with FrameBase
    
    if (s.find_compatible_models) {
      Model[] compatible = Frames.findCompatibleModels(frame, Models.fetchAll());
      s.compatible_models = new ModelSchema[compatible.length];
      s.frames[0].compatible_models = new String[compatible.length];
      int i = 0;
      for (Model m : compatible) {
        s.compatible_models[i] = (ModelSchema)Schema.schema(version, m).fillFromImpl(m);
        s.frames[0].compatible_models[i] = m._key.toString();
        i++;
      }
    }
    return s;
  }

  /** Export a single frame to the specified path. */
  public FramesV3 export(int version, FramesV3 s) {
    Frame fr = getFromDKV("key", s.frame_id.key());

    Log.info("ExportFiles processing (" + s.path + ")");
    InputStream csv = (fr).toCSV(true,false);
    export(csv,s.path, s.frame_id.key().toString(),s.force);
    return s;
  }

  // companion method to the export method
  private void export(InputStream csv, String path, String frameName, boolean force) {
    PersistManager pm = H2O.getPM();
    OutputStream os = null;
    try {
      os = pm.create(path, force);
      FileUtils.copyStream(csv, os, 4*1024*1024);
    }
    finally {
      if (os != null) {
        try {
          os.close();
          Log.info("Key '" + frameName +  "' was written to " + path + ".");
        }
        catch (Exception e) {
          Log.err(e);
        }
      }
    }
  }

  @SuppressWarnings("unused") // called through reflection by RequestServer
  public FramesV3 summary(int version, FramesV3 s) {
    Frame frame = getFromDKV("key", s.frame_id.key()); // safe

    for (Vec vec : frame.vecs()) {
      // Compute second pass of rollups: the histograms.
      if (!vec.isString()) {
        vec.bins();
      }
    }

    return doFetch(version, s, FrameV3.ColV2.FORCE_SUMMARY);
  }

  /** Remove an unlocked frame.  Fails if frame is in-use. */
  @SuppressWarnings("unused") // called through reflection by RequestServer
  public FramesV3 delete(int version, FramesV3 frames) {
    Frame frame = getFromDKV("key", frames.frame_id.key()); // safe
    frame.delete();             // lock & remove
    return frames;
  }

  /**
   * Remove ALL an unlocked frames.  Throws IAE for all deletes that failed
   * (perhaps because the Frames were locked & in-use).
   */
  @SuppressWarnings("unused") // called through reflection by RequestServer
  public FramesV3 deleteAll(int version, FramesV3 frames) {
    final Key[] keys = KeySnapshot.globalKeysOfClass(Frame.class);

    ArrayList<String> missing = new ArrayList<>();
    Futures fs = new Futures();
    for( int i = 0; i < keys.length; i++ ) {
      try {
        getFromDKV("(none)", keys[i]).delete(null, fs);
      } catch( IllegalArgumentException iae ) {
        missing.add(keys[i].toString());
      }
    }
    fs.blockForPending();
    if( missing.size() != 0 ) throw new H2OKeysNotFoundArgumentException("(none)", missing.toArray(new String[missing.size()]));
    return frames;
  }
}

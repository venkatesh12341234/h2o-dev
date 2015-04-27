package hex.tree;

import hex.*;
import jsr166y.CountedCompleter;
import water.*;
import water.H2O.H2OCountedCompleter;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.*;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static hex.ModelMetricsMultinomial.getHitRatioTable;

public abstract class SharedTree<M extends SharedTreeModel<M,P,O>, P extends SharedTreeModel.SharedTreeParameters, O extends SharedTreeModel.SharedTreeOutput> extends SupervisedModelBuilder<M,P,O> {
  public SharedTree( String name, P parms) { super(name,parms); /*only call init in leaf classes*/ }

  // Number of trees requested, including prior trees from a checkpoint
  protected int _ntrees;

  // The in-progress model being built
  protected M _model;

  // Number of columns in training set, not counting the response column
  protected int _ncols;

  // Initially predicted value (for zero trees)
  protected double _initialPrediction;

  // Sum of variable empirical improvement in squared-error.  The value is not scaled.
  private transient float[/*nfeatures*/] _improvPerVar;

  /** Initialize the ModelBuilder, validating all arguments and preparing the
   *  training frame.  This call is expected to be overridden in the subclasses
   *  and each subclass will start with "super.init();".  This call is made
   *  by the front-end whenever the GUI is clicked, and needs to be fast;
   *  heavy-weight prep needs to wait for the trainModel() call.
   *
   *  Validate the requested ntrees; precompute actual ntrees.  Validate
   *  the number of classes to predict on; validate a checkpoint.  */
  @Override public void init(boolean expensive) {
    super.init(expensive);

    if( _nclass > SharedTreeModel.SharedTreeParameters.MAX_SUPPORTED_LEVELS )
      error("_nclass", "Too many levels in response column!");

    if( _parms._min_rows < 0 )
      error("_min_rows", "Requested min_rows must be greater than 0");

    if( _parms._ntrees < 0 || _parms._ntrees > 100000 )
      error("_ntrees", "Requested ntrees must be between 1 and 100000");
    _ntrees = _parms._ntrees;   // Total trees in final model
    if( _parms._checkpoint ) {  // Asking to continue from checkpoint?
      Value cv = DKV.get(_parms._model_id);
      if( cv!=null ) {          // Look for prior model
        M checkpointModel = cv.get();
        if( _parms._ntrees < checkpointModel._output._ntrees+1 )
          error("_ntrees", "Requested ntrees must be between "+checkpointModel._output._ntrees+1+" and 100000");
        _ntrees = _parms._ntrees - checkpointModel._output._ntrees; // Needed trees
      }
    }
    if (_parms._max_depth <= 0) error ("_max_depth", "_max_depth must be > 0.");
    if (_parms._min_rows < 1) error ("_min_rows", "_min_rows must be >= 1.");
    if (_train != null && _train.numRows() < _parms._min_rows*2 ) // Need at least 2xmin_rows to split even once
      error("_min_rows", "The dataset size is too small to split for min_rows=" + _parms._min_rows + " , number of rows: " + _train.numRows() + " < 2*" + _parms._min_rows);
    if( _train != null )
      _ncols = _train.numCols()-1;
  }

  // --------------------------------------------------------------------------
  // Top-level tree-algo driver
  abstract protected class Driver extends H2OCountedCompleter<Driver> {

    // Top-level tree-algo driver function
    @Override protected void compute2() {
      _model = null;            // Resulting model!
      try {
        Scope.enter();          // Cleanup temp keys
        _parms.read_lock_frames(SharedTree.this); // Fetch & read-lock input frames
        init(true);             // Do any expensive tests & conversions now
        if( error_count() > 0 ) throw new IllegalArgumentException("Found validation errors: "+validationErrors());

        // New Model?  Or continuing from a checkpoint?
        if( _parms._checkpoint && DKV.get(_parms._model_id) != null ) {
          _model = DKV.get(_dest).get();
          _model.write_lock(_key); // do not delete previous model; we are extending it
        } else {                   // New Model
          // Compute the zero-tree error - guessing only the class distribution.
          // MSE is stddev squared when guessing for regression.
          // For classification, guess the largest class.
          _model = makeModel(_dest, _parms, 
                             initial_MSE(response(), response()), 
                             _valid == null ? Double.NaN : initial_MSE(response(),vresponse())); // Make a fresh model
          _model.delete_and_lock(_key);       // and clear & write-lock it (smashing any prior)
          _model._output._init_f = _initialPrediction;
        }

        // Compute the response domain; makes for nicer printouts
        String[] domain = _response.domain();
        assert (_nclass > 1 && domain != null) || (_nclass==1 && domain==null);
        if( _nclass==1 ) domain = new String[] {"r"}; // For regression, give a name to class 0

        // Compute class distribution, used to for initial guesses and to
        // upsample minority classes (if asked for).
        if( _nclass>1 ) {       // Classification?

          // Handle imbalanced classes by stratified over/under-sampling.
          // initWorkFrame sets the modeled class distribution, and
          // model.score() corrects the probabilities back using the
          // distribution ratios
          if(_model._output.isClassifier() && _parms._balance_classes ) {

            float[] trainSamplingFactors = new float[_train.lastVec().domain().length]; //leave initialized to 0 -> will be filled up below
            if (_parms._class_sampling_factors != null) {
              if (_parms._class_sampling_factors.length != _train.lastVec().domain().length)
                throw new IllegalArgumentException("class_sampling_factors must have " + _train.lastVec().domain().length + " elements");
              trainSamplingFactors = _parms._class_sampling_factors.clone(); //clone: don't modify the original
            }
            Frame stratified = water.util.MRUtils.sampleFrameStratified(_train, _train.lastVec(), trainSamplingFactors, (long)(_parms._max_after_balance_size*_train.numRows()), _parms._seed, true, false);
            if (stratified != _train) {
              _train = stratified;
              _response = stratified.lastVec();
              // Recompute distribution since the input frame was modified
              MRUtils.ClassDist cdmt2 = new MRUtils.ClassDist(_nclass).doAll(_response);
              _model._output._distribution = cdmt2.dist();
              _model._output._modelClassDist = cdmt2.rel_dist();
            }
          }
          Log.info("Prior class distribution: " + Arrays.toString(_model._output._priorClassDist));
          Log.info("Model class distribution: " + Arrays.toString(_model._output._modelClassDist));
        }

        // Also add to the basic working Frame these sets:
        //   nclass Vecs of current forest results (sum across all trees)
        //   nclass Vecs of working/temp data
        //   nclass Vecs of NIDs, allowing 1 tree per class

        // Current forest values: results of summing the prior M trees
        for( int i=0; i<_nclass; i++ )
          _train.add("Tree_"+domain[i], _response.makeZero());

        // Initial work columns.  Set-before-use in the algos.
        for( int i=0; i<_nclass; i++ )
          _train.add("Work_"+domain[i], _response.makeZero());

        // One Tree per class, each tree needs a NIDs.  For empty classes use a -1
        // NID signifying an empty regression tree.
        for( int i=0; i<_nclass; i++ )
          _train.add("NIDs_"+domain[i], _response.makeCon(_model._output._distribution==null ? 0 : (_model._output._distribution[i]==0?-1:0)));

        // Tag out rows missing the response column
        new ExcludeNAResponse().doAll(_train);

        // Variable importance: squared-error-improvement-per-variable-per-split
        _improvPerVar = new float[_ncols];

        // Sub-class tree-model-builder specific build code
        buildModel();
        done();                 // Job done!
      } catch( Throwable t ) {
        Job thisJob = DKV.getGet(_key);
        if (thisJob._state == JobState.CANCELLED) {
          Log.info("Job cancelled by user.");
        } else {
          t.printStackTrace();
          failed(t);
          throw t;
        }
      } finally {
        if( _model != null ) _model.unlock(_key);
        _parms.read_unlock_frames(SharedTree.this);
        if( _model==null ) Scope.exit();
        else {
          Key[] mms = _model._output._model_metrics;
          Scope.exit(_model._key,mms.length==0 ? null : mms[mms.length-1]);
        }
      }
      tryComplete();
    }

    // Abstract classes implemented by the tree builders
    abstract protected M makeModel( Key modelKey, P parms, double mse_train, double mse_valid );
    abstract protected void buildModel();
  }

  // --------------------------------------------------------------------------
  // Build an entire layer of all K trees
  protected DHistogram[][][] buildLayer(final Frame fr, final int nbins, final DTree ktrees[], final int leafs[], final DHistogram hcs[][][], boolean subset, boolean build_tree_one_node) {
    // Build K trees, one per class.

    // Build up the next-generation tree splits from the current histograms.
    // Nearly all leaves will split one more level.  This loop nest is
    //           O( #active_splits * #bins * #ncols )
    // but is NOT over all the data.
    ScoreBuildOneTree sb1ts[] = new ScoreBuildOneTree[_nclass];
    Vec vecs[] = fr.vecs();
    for( int k=0; k<_nclass; k++ ) {
      final DTree tree = ktrees[k]; // Tree for class K
      if( tree == null ) continue;
      // Build a frame with just a single tree (& work & nid) columns, so the
      // nested MRTask ScoreBuildHistogram in ScoreBuildOneTree does not try
      // to close other tree's Vecs when run in parallel.
      Frame fr2 = new Frame(Arrays.copyOf(fr._names,_ncols+1), Arrays.copyOf(vecs,_ncols+1));
      fr2.add(fr._names[idx_tree(k)],vecs[idx_tree(k)]);
      fr2.add(fr._names[idx_work(k)],vecs[idx_work(k)]);
      fr2.add(fr._names[idx_nids(k)],vecs[idx_nids(k)]);
      // Start building one of the K trees in parallel
      H2O.submitTask(sb1ts[k] = new ScoreBuildOneTree(this,k,nbins,tree,leafs,hcs,fr2, subset, build_tree_one_node, _improvPerVar));
    }
    // Block for all K trees to complete.
    boolean did_split=false;
    for( int k=0; k<_nclass; k++ ) {
      final DTree tree = ktrees[k]; // Tree for class K
      if( tree == null ) continue;
      sb1ts[k].join();
      if( sb1ts[k]._did_split ) did_split=true;
    }
    // The layer is done.
    return did_split ? hcs : null;
  }

  private static class ScoreBuildOneTree extends H2OCountedCompleter {
    final SharedTree _st;
    final int _k;               // The tree
    final int _nbins;           // Number of histogram bins
    final DTree _tree;
    final int _leafs[/*nclass*/];
    final DHistogram _hcs[/*nclass*/][][];
    final Frame _fr2;
    final boolean _subset;      // True if working a subset of cols
    final boolean _build_tree_one_node;
    float[] _improvPerVar;      // Squared Error improvement per variable per split
    
    boolean _did_split;
    ScoreBuildOneTree( SharedTree st, int k, int nbins, DTree tree, int leafs[], DHistogram hcs[][][], Frame fr2, boolean subset, boolean build_tree_one_node, float[] improvPerVar ) {
      _st   = st;
      _k    = k;
      _nbins= nbins;
      _tree = tree;
      _leafs= leafs;
      _hcs  = hcs;
      _fr2  = fr2;
      _subset = subset;
      _build_tree_one_node = build_tree_one_node;
      _improvPerVar = improvPerVar;
    }
    @Override public void compute2() {
      // Fuse 2 conceptual passes into one:
      // Pass 1: Score a prior DHistogram, and make new Node assignments
      // to every row.  This involves pulling out the current assigned Node,
      // "scoring" the row against that Node's decision criteria, and assigning
      // the row to a new child Node (and giving it an improved prediction).
      // Pass 2: Build new summary DHistograms on the new child Nodes every row
      // got assigned into.  Collect counts, mean, variance, min, max per bin,
      // per column.
      new ScoreBuildHistogram(this,_k, _st._ncols, _nbins,_tree, _leafs[_k],_hcs[_k],_subset).dfork(0,_fr2,_build_tree_one_node);
    }
    @Override public void onCompletion(CountedCompleter caller) {
      ScoreBuildHistogram sbh = (ScoreBuildHistogram)caller;
      //System.out.println(sbh.profString());

      final int leafk = _leafs[_k];
      int tmax = _tree.len();   // Number of total splits in tree K
      for( int leaf=leafk; leaf<tmax; leaf++ ) { // Visit all the new splits (leaves)
        DTree.UndecidedNode udn = _tree.undecided(leaf);
//        System.out.println((_st._nclass==1?"Regression":("Class "+_fr2.vecs()[_st._ncols].domain()[_k]))+",\n  Undecided node:"+udn);
        // Replace the Undecided with the Split decision
        DTree.DecidedNode dn = _st.makeDecided(udn,sbh._hcs[leaf-leafk]);
//        System.out.println("--> Decided node: " + dn +
//                           "  > Split: " + dn._split + " L/R:" + dn._split._n0+" + "+dn._split._n1);
        if( dn._split._col == -1 ) udn.do_not_split();
        else {
          _did_split = true;
          DTree.Split s = dn._split; // Accumulate squared error improvements per variable
          AtomicUtils.FloatArray.add(_improvPerVar,s.col(),(float)(s.pre_split_se()-s.se()));
        }
      }
      _leafs[_k]=tmax;          // Setup leafs for next tree level
      int new_leafs = _tree.len()-tmax;
      _hcs[_k] = new DHistogram[new_leafs][/*ncol*/];
      for( int nl = tmax; nl<_tree.len(); nl ++ )
        _hcs[_k][nl-tmax] = _tree.undecided(nl)._hs;
      if (new_leafs>0) _tree._depth++; // Next layer done but update tree depth only if new leaves are generated
    }
  }

  // --------------------------------------------------------------------------
  // Convenience accessor for a complex chunk layout.
  // Wish I could name the array elements nicer...
  protected int idx_resp(     ) { return _ncols; }
  protected int idx_oobt(     ) { return _ncols+1+_nclass+_nclass+_nclass; }
  protected int idx_tree(int c) { return _ncols+1+c; }
  protected int idx_work(int c) { return _ncols+1+_nclass+c; }
  protected int idx_nids(int c) { return _ncols+1+_nclass+_nclass+c; }

  protected Chunk chk_resp( Chunk chks[]        ) { return chks[idx_resp( )]; }
  protected Chunk chk_tree( Chunk chks[], int c ) { return chks[idx_tree(c)]; }
  protected Chunk chk_work( Chunk chks[], int c ) { return chks[idx_work(c)]; }
  protected Chunk chk_nids( Chunk chks[], int c ) { return chks[idx_nids(c)]; }
  // Out-of-bag trees counter - only one since it is shared via k-trees
  protected Chunk chk_oobt(Chunk chks[]) { return chks[_ncols+1+_nclass+_nclass+_nclass]; }

  protected final Vec vec_nids( Frame fr, int t) { return fr.vecs()[_ncols+1+_nclass+_nclass+t]; }
  protected final Vec vec_resp( Frame fr       ) { return fr.vecs()[_ncols]; }
  protected final Vec vec_tree( Frame fr, int c) { return fr.vecs()[idx_tree(c)]; }

  protected double[] data_row( Chunk chks[], int row, double[] data) {
    assert data.length == _ncols;
    for(int f=0; f<_ncols; f++) data[f] = chks[f].atd(row);
    return data;
  }

  // Builder-specific decision node
  abstract protected DTree.DecidedNode makeDecided( DTree.UndecidedNode udn, DHistogram hs[] );

  // Read the 'tree' columns, do model-specific math and put the results in the
  // fs[] array, and return the sum.  Dividing any fs[] element by the sum
  // turns the results into a probability distribution.
  abstract protected double score1( Chunk chks[], double fs[/*nclass*/], int row );

  // Call builder specific score code and then correct probabilities
  // if it is necessary.
  void score2(Chunk chks[], double fs[/*nclass*/], int row ) {
    double sum = score1(chks, fs, row);
    if( isClassifier()) {
      if( !Double.isInfinite(sum) && sum>0f ) ArrayUtils.div(fs, sum);
      ModelUtils.correctProbabilities(fs, _model._output._priorClassDist, _model._output._modelClassDist);
    }
  }

  // --------------------------------------------------------------------------
  // Tag out rows missing the response column
  class ExcludeNAResponse extends MRTask<ExcludeNAResponse> {
    @Override public void map( Chunk chks[] ) {
      Chunk ys = chk_resp(chks);
      for( int row=0; row<ys._len; row++ )
        if( ys.isNA(row) )
          for( int t=0; t<_nclass; t++ )
            chk_nids(chks,t).set(row, -1);
    }
  }

  // --------------------------------------------------------------------------
  transient long _timeLastScoreStart, _timeLastScoreEnd, _firstScore;
  protected double doScoringAndSaveModel(boolean finalScoring, boolean oob, boolean build_tree_one_node ) {
    double training_r2 = Double.NaN; // Training R^2 value, if computed
    long now = System.currentTimeMillis();
    if( _firstScore == 0 ) _firstScore=now;
    long sinceLastScore = now-_timeLastScoreStart;
    boolean updated = false;
    // Now model already contains tid-trees in serialized form
    if( _parms._score_each_iteration ||
        finalScoring ||
        (now-_firstScore < 4000) || // Score every time for 4 secs
        // Throttle scoring to keep the cost sane; limit to a 10% duty cycle & every 4 secs
        (sinceLastScore > 4000 && // Limit scoring updates to every 4sec
         (double)(_timeLastScoreEnd-_timeLastScoreStart)/sinceLastScore < 0.1) ) { // 10% duty cycle
      // If validation is specified we use a model for scoring, so we need to
      // update it!  First we save model with trees (i.e., make them available
      // for scoring) and then update it with resulting error
      _model.update(_key);  updated = true;

      Log.info("============================================================== ");
      SharedTreeModel.SharedTreeOutput out = _model._output;
      _timeLastScoreStart = now;
      // Score on training data
      Score sc = new Score(this,oob,_model._output.getModelCategory()).doAll(train(), build_tree_one_node);
      ModelMetricsSupervised mm = sc.makeModelMetrics(_model, _parms.train(), _parms._response_column);
      out._training_metrics = mm;
      if (oob) out._training_metrics._description = "Metrics reported on Out-Of-Bag training samples";
      String train_logloss = isClassifier() ? ", logloss is " + (float)(_nclass == 2 ? ((ModelMetricsBinomial)mm)._logloss : ((ModelMetricsMultinomial)mm)._logloss) : "";
      out._mse_train[out._ntrees] = mm._MSE; // Store score results in the model output
      training_r2 = mm.r2();
      Log.info("training r2 is "+(float)mm.r2()+", MSE is "+(float)mm._MSE + train_logloss + ", with "+_model._output._ntrees+"x"+_nclass+" trees (average of "+(1 + _model._output._treeStats._mean_leaves)+" nodes)"); //add 1 for root, which is not a leaf
      if (mm.hr() != null) {
        Log.info(getHitRatioTable(mm.hr()));
      }
      // Score again on validation data
      if( _parms._valid != null ) {
        Score scv = new Score(this,oob,_model._output.getModelCategory()).doAll(valid(), build_tree_one_node);
        ModelMetricsSupervised mmv = scv.makeModelMetrics(_model,_parms.valid(), _parms._response_column);
        out._mse_valid[out._ntrees] = mmv._MSE; // Store score results in the model output
        out._validation_metrics = mmv;
        String valid_logloss = isClassifier() ? ", logloss is " + (float)(_nclass == 2 ? ((ModelMetricsBinomial)mmv)._logloss : ((ModelMetricsMultinomial)mmv)._logloss) : "";
        Log.info("validation r2 is "+(float)mmv.r2()+", MSE is "+(float)mmv._MSE + valid_logloss);
        if (mmv.hr() != null) {
          Log.info(getHitRatioTable(mm.hr()));
        }
      }

      if( out._ntrees > 0 ) {    // Compute variable importances
        out._model_summary = createModelSummaryTable(out);
        out._scoring_history = createScoringHistoryTable(out);
        out._variable_importances = hex.ModelMetrics.calcVarImp(new hex.VarImp(_improvPerVar, out._names));
        Log.info(out._model_summary.toString());
        // For Debugging:
//        Log.info(out._scoring_history.toString());
//        Log.info(out._variable_importances.toString());
      }

      ConfusionMatrix cm = mm.cm();
      if( cm != null ) {
        if( cm._cm.length <= _parms._max_confusion_matrix_size) {
          Log.info(cm.toASCII());
        } else {
          Log.info("Confusion Matrix is too large (max_confusion_matrix_size=" + _parms._max_confusion_matrix_size
                  + "): " + _nclass + " classes.");
        }
        Log.info((_nclass > 1 ? "Total of " + cm.errCount() + " errors" : "Reported") + " on " + cm.totalRows() + " rows");
      }
      _timeLastScoreEnd = System.currentTimeMillis();
    }

    // Double update - after either scoring or variable importance
    if( updated ) _model.update(_key);
    return training_r2;
  }

  static int counter = 0;
  // helper for debugging
  @SuppressWarnings("unused")
  static protected void printGenerateTrees(DTree[] trees) {
    for( DTree dtree : trees )
      if( dtree != null ) {
        try {
          PrintWriter writer = new PrintWriter("/tmp/h2o-dev.tree" + ++counter + ".txt", "UTF-8");
          writer.println(dtree.root().toString2(new StringBuilder(), 0));
          writer.close();
        } catch (FileNotFoundException|UnsupportedEncodingException e) {
          e.printStackTrace();
        }
        System.out.println(dtree.root().toString2(new StringBuilder(), 0));
      }
  }

  double initial_MSE( Vec train, Vec test ) {
    if( train.isEnum() ) {
      // Guess the class of the most populous class; call the fraction of those
      // Q.  Then Q of them are "mostly correct" - error is (1-Q) per element.
      // The remaining 1-Q elements are "mostly wrong", error is Q (our guess,
      // which is wrong).
      int cls = ArrayUtils.maxIndex(train.bins());
      double guess = train.bins()[cls]/(train.length()-train.naCnt());
      double actual= test .bins()[cls]/(test .length()-test .naCnt());
      return guess*guess+actual-2.0*actual*guess;
    } else {              // Regression
      // Guessing the training data mean, but actual is validation set mean
      double stddev = test.sigma();
      double bias = train.mean()-test.mean();
      return stddev*stddev+bias*bias;
    }
  }

  // Helper to unify use of M-T RNG
  public static Random createRNG(long seed) {
    return new RandomUtils.MersenneTwisterRNG((int)(seed>>32L),(int)seed );
//    return RandomUtils.getRNG((int)(seed>>32L),(int)seed ); //for later
  }

  private TwoDimTable createScoringHistoryTable(SharedTreeModel.SharedTreeOutput _output) {
    List<String> colHeaders = new ArrayList<>();
    List<String> colTypes = new ArrayList<>();
    List<String> colFormat = new ArrayList<>();
    colHeaders.add("Number of Trees"); colTypes.add("long"); colFormat.add("%d");
    colHeaders.add("Training MSE"); colTypes.add("double"); colFormat.add("%.5f");
    if (valid() != null) {
      colHeaders.add("Validation MSE"); colTypes.add("double"); colFormat.add("%.5f");
    }

    final int rows = _output._mse_train.length-1;
    TwoDimTable table = new TwoDimTable(
            "Scoring History", null,
            new String[rows],
            colHeaders.toArray(new String[0]),
            colTypes.toArray(new String[0]),
            colFormat.toArray(new String[0]),
            "");
    int row = 0;
    for( int i = 1; i<=rows; i++ ) {
      int col = 0;
      assert(row < table.getRowDim());
      assert(col < table.getColDim());
      table.set(row, col++, i);
      table.set(row, col++, _output._mse_train[i]);
      if (_valid != null) table.set(row, col++, _output._mse_valid[i]);
      row++;
    }
    return table;
  }

  private TwoDimTable createModelSummaryTable(SharedTreeModel.SharedTreeOutput _output) {
    List<String> colHeaders = new ArrayList<>();
    List<String> colTypes = new ArrayList<>();
    List<String> colFormat = new ArrayList<>();

    colHeaders.add("Number of Trees"); colTypes.add("long"); colFormat.add("%d");

    colHeaders.add("Min. Depth"); colTypes.add("long"); colFormat.add("%d");
    colHeaders.add("Max. Depth"); colTypes.add("long"); colFormat.add("%d");
    colHeaders.add("Mean Depth"); colTypes.add("double"); colFormat.add("%.5f");

    colHeaders.add("Min. Leaves"); colTypes.add("long"); colFormat.add("%d");
    colHeaders.add("Max. Leaves"); colTypes.add("long"); colFormat.add("%d");
    colHeaders.add("Mean Leaves"); colTypes.add("double"); colFormat.add("%.5f");

    final int rows = 1;
    TwoDimTable table = new TwoDimTable(
            "Model Summary", null,
            new String[rows],
            colHeaders.toArray(new String[0]),
            colTypes.toArray(new String[0]),
            colFormat.toArray(new String[0]),
            "");
    int row = 0;
    int col = 0;
    table.set(row, col++, _output._treeStats._num_trees);
    table.set(row, col++, _output._treeStats._min_depth);
    table.set(row, col++, _output._treeStats._max_depth);
    table.set(row, col++, _output._treeStats._mean_depth);
    table.set(row, col++, _output._treeStats._min_leaves);
    table.set(row, col++, _output._treeStats._max_leaves);
    table.set(row, col++, _output._treeStats._mean_leaves);
    return table;
  }
}
